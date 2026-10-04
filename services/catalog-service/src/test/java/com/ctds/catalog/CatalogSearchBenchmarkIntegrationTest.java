package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 目录检索基准探针（WBS-3.3.7 hifi §4；章程 4.2 性能敏感白名单：目录检索响应 ≤500ms）：
 * 万级目录数据集（资源 1 万 + 产品 1 万；造数单一事实源 = scripts/benchmark/catalog-seed.sql，
 * 占位参数替换后经 JdbcTemplate 逐语句执行）之上，MockMvc 全链打 R6 检索端点，五场景各 50 轮，
 * 记录每轮耗时算 P50/P95/max（最近邻秩分位），P95 ≤500ms 硬判定；结果 JSON 落
 * target/benchmark/w337-bench-*.json（target 不入库），数字摘要入开发日志（业务可读）。
 *
 * <p><b>诚实边界（结果留痕必写）</b>：容器内单机口径——资格门 {@code @MockitoBean} stub（无真实
 * subject HTTP 往返与生产网络）、MockMvc 进程内链路（无网关与网络层）、串行 50 轮无并发加压；
 * 正式并发压测与阶梯加压归 WBS-4.2.1（本卡造数脚本与数据构成 = 其"性能测试数据集与脚本"份额）。</p>
 *
 * <p>真实 MySQL 8 容器（类级独立库名，Ryuk 自灭）；演示库 ctds_catalog 零接触（Q3-A）；
 * 无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSearchBenchmarkIntegrationTest {

    /** 造数脚本占位参数默认值（与 hifi §4.1 默认口径一致；4.2.1 复用时改脚本默认值或此处均可）。 */
    private static final String SPACE_BASE = "990001";
    private static final String ROW_TOTAL = "10000";
    private static final String PER_SPACE = "2500";

    /** 每场景轮数（hifi §4.2 定稿）。 */
    private static final int ROUNDS = 50;

    /** 达标线：P95 ≤ 500ms（章程 4.2 指标锚）。 */
    private static final long P95_BUDGET_MS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTS = "/api/v1/data-products";
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_bench_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @BeforeEach
    void defaultsAdmitted() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
    }

    // ==== 五场景 × 50 轮：P50/P95/max 达标判定 + JSON 留痕 ====

    @Test
    void searchP95WithinBudgetAcrossFiveScenariosOnTenThousandProducts() throws Exception {
        seedTenThousandRows();

        final Map<String, ScenarioResult> results = new LinkedHashMap<>();
        results.put("01-full-first-page", runScenario("全量第一页（无过滤）",
                request().param("pageNum", "1").param("pageSize", "10"), 10000L));
        results.put("02-category-subtree", runScenario("类目过滤（finance 含子树）",
                request().param("pageNum", "1").param("pageSize", "10").param("categoryCode", "finance"), null));
        results.put("03-keyword-hit", runScenario("关键词命中（约 20% 固定检索词）",
                request().param("pageNum", "1").param("pageSize", "10").param("keyword", "基准检索关键词"), 2000L));
        results.put("04-keyword-miss", runScenario("关键词零命中",
                request().param("pageNum", "1").param("pageSize", "10").param("keyword", "零命中无匹配专用词"), 0L));
        results.put("05-deep-paging", runScenario("深翻页（第 1000 页，offset 逼近万级末页）",
                request().param("pageNum", "1000").param("pageSize", "10"), 10000L));

        results.values().forEach(r -> assertThat(r.p95Ms())
                .as("场景[%s] P95 ≤ %dms（容器内口径）", r.label(), P95_BUDGET_MS)
                .isLessThanOrEqualTo(P95_BUDGET_MS));

        writeResultJson(results);
    }

    // ==== 造数：读单一事实源脚本，替换占位后逐语句执行 ====

    private void seedTenThousandRows() throws IOException {
        final Path script = Path.of("..", "..", "scripts", "benchmark", "catalog-seed.sql");
        assertThat(script).as("造数脚本存在（scripts/benchmark/catalog-seed.sql）").exists();
        final String sql = Files.readString(script, StandardCharsets.UTF_8)
                .replace("@SPACE_BASE@", SPACE_BASE)
                .replace("@ROW_TOTAL@", ROW_TOTAL)
                .replace("@PER_SPACE@", PER_SPACE);
        // 脚本受控（语句内无分号字面量）：去注释行后按分号切分逐条执行
        final String stripped = sql.replaceAll("(?m)^--.*$", "");
        for (final String statement : stripped.split(";")) {
            final String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
        assertThat(productCount()).as("造数后基准产品行数").isEqualTo(Long.parseLong(ROW_TOTAL));
        assertThat(keywordHitRows()).as("固定检索词命中行数（精确 20%）").isEqualTo(2000L);
    }

    private ScenarioResult runScenario(final String label, final MockHttpServletRequestBuilder builder,
            final Long expectedTotal) throws Exception {
        final List<Long> samples = new ArrayList<>(ROUNDS);
        long totalRows = -1;
        for (int i = 0; i < ROUNDS; i++) {
            final long startedAt = System.nanoTime();
            final MvcResult result = mockMvc.perform(builder).andReturn();
            final long finishedAt = System.nanoTime();
            assertThat(result.getResponse().getStatus()).as("场景[%s] 检索请求成功", label).isEqualTo(200);
            totalRows = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("total").asLong();
            samples.add((finishedAt - startedAt) / 1_000_000L);
        }
        if (expectedTotal == null) {
            assertThat(totalRows).as("场景[%s] 命中行数为正（类目子树展开）", label).isPositive();
        } else {
            assertThat(totalRows).as("场景[%s] 命中行数符合造数分布", label).isEqualTo(expectedTotal);
        }
        final long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return new ScenarioResult(label,
                sorted[(int) Math.ceil(ROUNDS * 0.50) - 1],
                sorted[(int) Math.ceil(ROUNDS * 0.95) - 1],
                sorted[ROUNDS - 1], totalRows);
    }

    /**
     * 结果 JSON 落 target/benchmark/（target 不入库）；数字摘要随开发日志留痕。
     * 不写 stdout（AGENTS §4 禁止提交调试输出；评审④ P3 处置）——数字留痕以本 JSON + 开发日志为准。
     */
    private void writeResultJson(final Map<String, ScenarioResult> results) throws IOException {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("card", "WBS-3.3.7");
        root.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.putObject("dataset")
                .put("datasets", ROW_TOTAL)
                .put("products", ROW_TOTAL)
                .put("spaceSegment", SPACE_BASE + "~" + (Integer.parseInt(SPACE_BASE) + 3))
                .put("categoryNodes", 24)
                .put("keywordHitRatio", "20%")
                .put("keyword", "基准检索关键词");
        root.put("roundsPerScenario", ROUNDS);
        root.putObject("threshold")
                .put("metric", "P95").put("unit", "ms").put("budget", P95_BUDGET_MS)
                .put("verdict", "PASS");
        root.putObject("boundary")
                .put("qualificationGate", "stub（无真实 subject HTTP 往返）")
                .put("channel", "MockMvc 进程内全链（无网络与网关）")
                .put("load", "串行 50 轮，无并发加压——正式并发压测与阶梯加压归 WBS-4.2.1")
                .put("warmup", "未设预热轮：首轮含 JIT/冷启动开销，maxMs 受其影响（P95 取 50 轮最近邻秩，影响有限）");
        final ArrayNode scenarios = root.putArray("scenarios");
        results.values().forEach(r -> {
            final ObjectNode node = scenarios.addObject();
            node.put("name", r.label())
                    .put("p50Ms", r.p50Ms())
                    .put("p95Ms", r.p95Ms())
                    .put("maxMs", r.maxMs())
                    .put("totalRows", r.totalRows());
        });
        final Path dir = Path.of("target", "benchmark");
        Files.createDirectories(dir);
        final Path file = dir.resolve("w337-bench-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    // ==== 助手 ====

    private MockHttpServletRequestBuilder request() {
        return get(PRODUCTS).header("X-Ctds-Subject", "bench-reader").header("X-Ctds-Roles", PROVIDER);
    }

    private long productCount() {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_product WHERE provider_subject_no LIKE 'bench-provider-%'",
                Long.class);
        return count == null ? 0 : count;
    }

    private long keywordHitRows() {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_product WHERE intro LIKE '%基准检索关键词%'", Long.class);
        return count == null ? 0 : count;
    }

    /** 单场景结果（毫秒分位 + 命中行数；分位取最近邻秩）。 */
    private record ScenarioResult(String label, long p50Ms, long p95Ms, long maxMs, long totalRows) {
    }
}
