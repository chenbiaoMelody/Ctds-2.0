package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.metering.support.BillingSourceSchemas;
import com.ctds.metering.support.SharedMySqlContainer;
import com.ctds.metering.support.SourceSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 计量流水读面基准探针（WBS-3.7.8 hifi §1-C；AGENTS 行 76 性能敏感路径）：
 * 测试容器库内造 <b>10000</b> 条 {@code metering_flow}（20 合约 × 500 条，occurred_at 跨 12 个
 * 自然月、四源轮转、source_no 唯一、20 个主体号），MockMvc 全链打三个读面五场景，
 * 每场景前 5 轮预热不计入、后 50 轮计时，算 P50 / P95 / max，P95 ≤500ms 硬判定。
 * 结果 JSON 落 {@code target/benchmark/w378-bench-*.json}（target 不入库），数字摘要入开发日志。
 *
 * <p><b>诚实边界（结果留痕必写）</b>：容器内单机口径——MockMvc 进程内链路（无网关与网络层）、
 * 串行 50 轮无并发加压、单表 1 万行数据集；门户 ≥1000 并发与网关 ≥2000 TPS 归 WBS-4.2.2/4.2.3。
 * 演示库零触碰（独立库名，Ryuk 自灭）；无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MeteringFlowBenchmarkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_DB = "ctds_metering_bench_contract_it";

    /** 数据集：20 合约 × 500 条 = 10000（hifi §1-C 定稿）。 */
    private static final int CONTRACTS = 20;
    private static final int FLOWS_PER_CONTRACT = 500;
    private static final int TOTAL_FLOWS = CONTRACTS * FLOWS_PER_CONTRACT;
    /** 每场景计轮次与预热轮（hifi §1-C）。 */
    private static final int WARMUP_ROUNDS = 5;
    private static final int ROUNDS = 50;
    /** 达标线：P95 ≤ 500ms（章程性能锚）。 */
    private static final long P95_BUDGET_MS = 500;
    private static final String PROVIDER = "S-bench-provider";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_bench_it");
        registry.add("ctds.metering.source-schema.contract", () -> CONTRACT_DB);
    }

    @BeforeAll
    static void prepareSourceSchemas() {
        BillingSourceSchemas.createContractTables(SourceSchemas.jdbcTemplateFor(CONTRACT_DB));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void threeReadSurfacesP95WithinBudgetOnTenThousandFlows() throws Exception {
        final long seedMillis = seedTenThousandFlows();
        assertThat(totalFlows()).as("造数后计量流水行数").isEqualTo(TOTAL_FLOWS);

        final Map<String, ScenarioResult> results = new LinkedHashMap<>();
        results.put("01-own-flows", ownFlowsScenario());
        results.put("02-own-summary", ownSummaryScenario());
        results.put("03-governance-first-page", governanceScenario("运营全量首页（无过滤）",
                request().param("pageNum", "1").param("pageSize", "20"), 20));
        results.put("04-governance-contract-range", governanceScenario("合约 + 时间区间过滤",
                request().param("contractNo", contractNo(2))
                        .param("from", "2025-11-01 00:00:00").param("to", "2026-04-30 23:59:59"),
                null));
        results.put("05-governance-deep-page", governanceScenario("越页（pageNum=999）",
                request().param("pageNum", "999").param("pageSize", "20"), 0));

        results.values().forEach(r -> assertThat(r.p95Ms())
                .as("场景[%s] P95 ≤ %dms（容器内口径）", r.label(), P95_BUDGET_MS)
                .isLessThanOrEqualTo(P95_BUDGET_MS));

        writeResultJson(results, seedMillis);
    }

    // ==== 场景 ====

    /** 场景① R-M1 本方逐条流水（端点无分页参数，按真实形状读单合约全量 500 条）。 */
    private ScenarioResult ownFlowsScenario() throws Exception {
        final MockHttpServletRequestBuilder builder = get(
                "/api/v1/metering/contracts/" + contractNo(1) + "/flows")
                .header("X-Ctds-Subject", requesterNo(1)).header("X-Ctds-Roles", "requester");
        return measure("R-M1 本方逐条流水（单合约 500 条）", builder, "items", FLOWS_PER_CONTRACT);
    }

    /** 场景② R-M2 分期汇总（单合约全周期 = 12 个自然月）。 */
    private ScenarioResult ownSummaryScenario() throws Exception {
        final MockHttpServletRequestBuilder builder = get(
                "/api/v1/metering/contracts/" + contractNo(2) + "/summary")
                .header("X-Ctds-Subject", requesterNo(2)).header("X-Ctds-Roles", "requester");
        return measure("R-M2 分期汇总（单合约 12 期）", builder, "periods", 12);
    }

    /** 场景③~⑤ R-M4 运营全量分页（无过滤 / 合约 + 区间过滤 / 越页）。 */
    private ScenarioResult governanceScenario(final String label,
            final MockHttpServletRequestBuilder builder, final Integer expectedPageSize)
            throws Exception {
        final List<Long> samples = new ArrayList<>(ROUNDS);
        long total = -1;
        int pageSize = -1;
        for (int i = 0; i < WARMUP_ROUNDS + ROUNDS; i++) {
            final long startedAt = System.nanoTime();
            final MvcResult result = mockMvc.perform(builder).andReturn();
            final long finishedAt = System.nanoTime();
            assertThat(result.getResponse().getStatus()).as("场景[%s] 请求成功", label).isEqualTo(200);
            final JsonNode data = MAPPER.readTree(
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
            total = data.path("total").asLong();
            pageSize = data.path("list").size();
            if (i >= WARMUP_ROUNDS) {
                samples.add((finishedAt - startedAt) / 1_000_000L);
            }
        }
        if (expectedPageSize != null) {
            assertThat(pageSize).as("场景[%s] 本页行数", label).isEqualTo(expectedPageSize);
        }
        if ("运营全量首页（无过滤）".equals(label)) {
            assertThat(total).as("全量数据集总数").isEqualTo(TOTAL_FLOWS);
        }
        if ("越页（pageNum=999）".equals(label)) {
            assertThat(total).as("E12 越页时 total 仍为真实总数").isEqualTo(TOTAL_FLOWS);
        }
        return finish(label, samples, total, pageSize);
    }

    /** 通用计时（R-M1 / R-M2：断言数组长度后统计）。 */
    private ScenarioResult measure(final String label, final MockHttpServletRequestBuilder builder,
            final String arrayField, final int expectedSize) throws Exception {
        final List<Long> samples = new ArrayList<>(ROUNDS);
        int size = -1;
        for (int i = 0; i < WARMUP_ROUNDS + ROUNDS; i++) {
            final long startedAt = System.nanoTime();
            final MvcResult result = mockMvc.perform(builder).andReturn();
            final long finishedAt = System.nanoTime();
            assertThat(result.getResponse().getStatus()).as("场景[%s] 请求成功", label).isEqualTo(200);
            size = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path(arrayField).size();
            if (i >= WARMUP_ROUNDS) {
                samples.add((finishedAt - startedAt) / 1_000_000L);
            }
        }
        assertThat(size).as("场景[%s] 返回条目数", label).isEqualTo(expectedSize);
        // total = -1：R-M1 / R-M2 无分页语义，本场景不采集"分页总数"（避免把条目数误记成 total）
        return finish(label, samples, -1, size);
    }

    private ScenarioResult finish(final String label, final List<Long> samples, final long total,
            final int pageSize) {
        final long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return new ScenarioResult(label,
                sorted[(int) Math.ceil(ROUNDS * 0.50) - 1],
                sorted[(int) Math.ceil(ROUNDS * 0.95) - 1],
                sorted[ROUNDS - 1], total, pageSize);
    }

    // ==== 造数：测试容器库内批量插入 10000 条 ====

    private long seedTenThousandFlows() {
        final JdbcTemplate source = SourceSchemas.jdbcTemplateFor(CONTRACT_DB);
        for (int c = 1; c <= CONTRACTS; c++) {
            source.update("INSERT INTO contract (contract_no, product_id, product_name, "
                            + "provider_subject_no, requester_subject_no, pricing_model, "
                            + "price_amount, status, effective_at) "
                            + "VALUES (?, 301, '基准数据集', ?, ?, 'PER_CALL', 1.00, "
                            + "'EFFECTIVE', '2025-01-01 00:00:00')",
                    contractNo(c), PROVIDER, requesterNo(c));
        }
        final List<Object[]> batch = new ArrayList<>(FLOWS_PER_CONTRACT);
        final long startedAt = System.nanoTime();
        for (int c = 1; c <= CONTRACTS; c++) {
            batch.clear();
            for (int i = 0; i < FLOWS_PER_CONTRACT; i++) {
                // 跨 12 个自然月均匀分布，四源轮转，源单号唯一
                final int month = i % 12;
                final int day = (i / 12) % 28 + 1;
                final int hour = i % 24;
                final LocalDateTime occurredAt = LocalDateTime.of(2026, 1, 1, 0, 0)
                        .plusMonths(month).withDayOfMonth(day).withHour(hour);
                batch.add(new Object[] {contractNo(c), requesterNo(c), sourceType(i),
                    "bench-" + c + "-" + i, Timestamp.valueOf(occurredAt)});
            }
            jdbcTemplate.batchUpdate("INSERT INTO metering_flow (contract_no, requester_no, "
                    + "source_type, source_no, action, resource, occurred_at) "
                    + "VALUES (?, ?, ?, ?, 'gateway.delivery.metering', NULL, ?)", batch);
        }
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String sourceType(final int index) {
        return switch (index % 4) {
            case 0 -> "GATEWAY_API";
            case 1 -> "FILE_DELIVERY";
            case 2 -> "SANDBOX_JOB";
            default -> "SANDBOX_EXPORT";
        };
    }

    private static String contractNo(final int index) {
        return String.format("CO80%04d", index);
    }

    private static String requesterNo(final int index) {
        return String.format("S-bench-%04d", index);
    }

    private long totalFlows() {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow", Long.class);
        return count == null ? 0L : count;
    }

    // ==== 结果留痕（target 不入库；不写 stdout） ====

    private void writeResultJson(final Map<String, ScenarioResult> results, final long seedMillis)
            throws IOException {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("card", "WBS-3.7.8");
        root.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.putObject("dataset")
                .put("meteringFlows", TOTAL_FLOWS)
                .put("contracts", CONTRACTS)
                .put("flowsPerContract", FLOWS_PER_CONTRACT)
                .put("months", 12)
                .put("sourceTypes", 4)
                .put("requesters", CONTRACTS)
                .put("seedMillis", seedMillis);
        root.put("roundsPerScenario", ROUNDS);
        root.put("warmupRounds", WARMUP_ROUNDS);
        final boolean allWithinBudget = results.values().stream()
                .allMatch(r -> r.p95Ms() <= P95_BUDGET_MS);
        root.putObject("threshold")
                .put("metric", "P95").put("unit", "ms").put("budget", P95_BUDGET_MS)
                .put("verdict", allWithinBudget ? "PASS" : "FAIL");
        root.putObject("boundary")
                .put("channel", "MockMvc 进程内全链（无网关与网络层）")
                .put("load", "串行 50 轮，无并发加压——门户 ≥1000 并发与网关 ≥2000 TPS 归 WBS-4.2.2/4.2.3")
                .put("dataset", "单表 1 万行（容器内单机口径）");
        final ArrayNode scenarios = root.putArray("scenarios");
        results.values().forEach(r -> {
            final ObjectNode node = scenarios.addObject();
            node.put("name", r.label())
                    .put("p50Ms", r.p50Ms())
                    .put("p95Ms", r.p95Ms())
                    .put("maxMs", r.maxMs())
                    .put("total", r.total())
                    .put("pageSize", r.pageSize());
        });
        final Path dir = Path.of("target", "benchmark");
        Files.createDirectories(dir);
        final Path file = dir.resolve("w378-bench-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".json");
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/v1/metering/flows/governance")
                .header("X-Ctds-Subject", "S-bench-admin").header("X-Ctds-Roles", "admin");
    }

    /** 单场景结果（毫秒分位取最近邻秩 + 命中总数）。 */
    private record ScenarioResult(String label, long p50Ms, long p95Ms, long maxMs, long total,
            int pageSize) {
    }
}
