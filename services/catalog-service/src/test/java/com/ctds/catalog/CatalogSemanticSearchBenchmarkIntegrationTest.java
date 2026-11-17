package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
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
import java.util.List;
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
 * 目录语义检索基准探针（WBS-3.10.2 hifi §7 T9 / B5）：1k/10k 演示数据（复用
 * scripts/benchmark/catalog-seed.sql），记录冷启动首查耗时与预热后稳态 P50/P95/max；
 * 10k 稳态 P95 目标 ≤500ms。容器内单机、串行、Mock 资格门、无并发加压——不宣称生产性能。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSemanticSearchBenchmarkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEMANTIC = "/api/v1/catalog/semantic-search";
    private static final String PROVIDER = "provider";
    private static final long P95_BUDGET_MS = 500L;
    private static final int ROWS_1K = 1000;
    private static final int ROWS_10K = 10000;
    private static final int ROUNDS_1K = 20;
    private static final int ROUNDS_10K = 50;
    private static final String SPACE_BASE = "990001";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_semantic_bench_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @MockitoBean
    private SpaceMembershipPort spaceMembershipPort;

    @BeforeEach
    void defaultsAdmitted() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
    }

    @Test
    void semanticSearchShouldStayWithinBudgetOnDemoScale() throws Exception {
        final ScenarioResult oneK = runScale(ROWS_1K, ROWS_1K / 4, ROUNDS_1K);
        final ScenarioResult tenK = runScale(ROWS_10K, ROWS_10K / 4, ROUNDS_10K);

        assertThat(tenK.p95Ms()).as("10k 稳态 P95 ≤ %dms（容器内口径）", P95_BUDGET_MS)
                .isLessThanOrEqualTo(P95_BUDGET_MS);
        writeResultJson(oneK, tenK);
    }

    private ScenarioResult runScale(final int rows, final int perSpace, final int rounds)
            throws Exception {
        cleanSeedRows();
        seed(rows, perSpace);
        final long coldStartMs = measureOnce();
        final List<Long> samples = new ArrayList<>(rounds);
        long totalRows = -1L;
        for (int i = 0; i < rounds; i++) {
            final long startedAt = System.nanoTime();
            final MvcResult result = mockMvc.perform(request()).andReturn();
            final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            totalRows = MAPPER.readTree(result.getResponse()
                            .getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("total").asLong();
            samples.add(elapsedMs);
        }
        assertThat(totalRows).as("无相似度阈值：total = 候选产品数").isEqualTo(rows);
        final long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return new ScenarioResult(rows, coldStartMs,
                sorted[(int) Math.ceil(rounds * 0.50) - 1],
                sorted[(int) Math.ceil(rounds * 0.95) - 1],
                sorted[rounds - 1], totalRows);
    }

    private long measureOnce() throws Exception {
        final long startedAt = System.nanoTime();
        final MvcResult result = mockMvc.perform(request()).andReturn();
        final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return elapsedMs;
    }

    private MockHttpServletRequestBuilder request() {
        return get(SEMANTIC).header("X-Ctds-Subject", "bench-reader")
                .header("X-Ctds-Roles", PROVIDER)
                .param("q", "基准检索关键词")
                .param("pageNum", "1")
                .param("pageSize", "10");
    }

    private void cleanSeedRows() {
        jdbc.update("DELETE FROM product_embedding");
        jdbc.update("DELETE FROM product_favorite");
        jdbc.update("DELETE FROM product_subscription");
        jdbc.update("DELETE FROM product_interaction_log");
        jdbc.update("DELETE FROM product_action_log");
        jdbc.update("DELETE FROM data_product");
        jdbc.update("DELETE FROM dataset WHERE data_no LIKE 'DSBENCH%'");
    }

    private void seed(final int rows, final int perSpace) throws IOException {
        final Path script = Path.of("..", "..", "scripts", "benchmark", "catalog-seed.sql");
        assertThat(script).as("造数脚本存在（scripts/benchmark/catalog-seed.sql）").exists();
        final String sql = Files.readString(script, StandardCharsets.UTF_8)
                .replace("@SPACE_BASE@", SPACE_BASE)
                .replace("@ROW_TOTAL@", String.valueOf(rows))
                .replace("@PER_SPACE@", String.valueOf(perSpace));
        final String stripped = sql.replaceAll("(?m)^--.*$", "");
        for (final String statement : stripped.split(";")) {
            final String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_product WHERE provider_subject_no LIKE 'bench-provider-%'",
                Long.class);
        assertThat(count).as("造数后基准产品行数").isEqualTo((long) rows);
    }

    private void writeResultJson(final ScenarioResult oneK, final ScenarioResult tenK)
            throws IOException {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("card", "WBS-3.10.2");
        root.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.putObject("threshold")
                .put("metric", "P95")
                .put("unit", "ms")
                .put("budget", P95_BUDGET_MS)
                .put("verdict", tenK.p95Ms() <= P95_BUDGET_MS ? "PASS" : "FAIL");
        root.putObject("boundary")
                .put("qualificationGate", "stub（无真实 subject HTTP 往返）")
                .put("channel", "MockMvc 进程内全链（无网络与网关）")
                .put("load", "串行轮次，无并发加压——正式并发压测与阶梯加压归 WBS-4.2.1")
                .put("warmup", "coldStartMs = 首查惰性向量计算/回写；P95 取预热后稳态轮次");
        final ArrayNode scenarios = root.putArray("scenarios");
        addScenario(scenarios, "01-1k", oneK);
        addScenario(scenarios, "02-10k", tenK);
        final Path dir = Path.of("target", "benchmark");
        Files.createDirectories(dir);
        final Path file = dir.resolve("w3102-semantic-bench-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".json");
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private static void addScenario(final ArrayNode scenarios, final String name,
            final ScenarioResult result) {
        final ObjectNode node = scenarios.addObject();
        node.put("name", name)
                .put("rows", result.rows())
                .put("coldStartMs", result.coldStartMs())
                .put("p50Ms", result.p50Ms())
                .put("p95Ms", result.p95Ms())
                .put("maxMs", result.maxMs())
                .put("totalRows", result.totalRows());
    }

    private record ScenarioResult(int rows, long coldStartMs, long p50Ms, long p95Ms, long maxMs,
            long totalRows) {
    }
}
