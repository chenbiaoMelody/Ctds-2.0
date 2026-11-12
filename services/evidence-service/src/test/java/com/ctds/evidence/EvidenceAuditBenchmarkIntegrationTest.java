package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 存证审计检索基准探针（WBS-3.8.6 hifi §1 T8；AGENTS §4 性能敏感路径）：
 * 万级存证流水（直插 1 万条：四环节 × 三状态混合）之上，MockMvc 全链打审计读面五场景，
 * 每场景 5 轮预热 + 20 轮采样，P50/P95/max 落 {@code target/benchmark/evidence-audit.json}，
 * **P95 ≤500ms 硬判定**（章程 4.2 指标锚）。
 *
 * <p><b>诚实边界（结果 JSON 内亦注明）</b>：容器内单机口径——MockMvc 进程内链路（无网关与网络层）、
 * 串行 20 轮无并发加压、造数为直插（非真实业务链路写入）；正式并发压测与阶梯加压归 WBS-4.2.1。</p>
 *
 * <p>真实 MySQL 8 容器（类级独立库名，Ryuk 自灭）；演示库 ctds_evidence 零接触；无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class EvidenceAuditBenchmarkIntegrationTest {

    /** 造数总量（hifi T8：万级流水）。 */
    private static final int ROW_TOTAL = 10_000;

    /** 每场景采样轮数与预热轮数（hifi T8 定稿）。 */
    private static final int ROUNDS = 20;
    private static final int WARMUP_ROUNDS = 5;

    /** 达标线：P95 ≤ 500ms（章程 4.2 指标锚）。 */
    private static final long P95_BUDGET_MS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECORDS = "/api/v1/evidence/records";
    private static final String ANOMALIES = "/api/v1/evidence/audit/anomalies";
    private static final String AUDIT_OVERVIEW = "/api/v1/evidence/audit/overview";
    private static final String ADMIN = "S-admin-evidence";
    private static final List<String> STAGES =
            List.of("AUTHORIZATION", "CONTRACT", "DELIVERY", "USAGE");

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_bench_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
        jdbc.update("DELETE FROM evidence_access_log");
        jdbc.update("DELETE FROM evidence_audit_report");
        jdbc.update("DELETE FROM evidence_report_no_seq");
    }

    /** T8：五场景 × 20 轮（各 5 轮预热）P95 ≤500ms + 结果 JSON 留痕。 */
    @Test
    void auditReadFaceP95WithinBudgetOnTenThousandRecords() throws Exception {
        seedTenThousandRows();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_record", Long.class))
                .as("造数后流水行数").isEqualTo(ROW_TOTAL);

        final Map<String, ScenarioResult> results = new LinkedHashMap<>();
        results.put("01-records-stage-page", runScenario("存证清单按环节分页（USAGE 2500 条取首页）",
                request(RECORDS).param("stageType", "USAGE")
                        .param("pageNum", "1").param("pageSize", "100"), 2500L));
        results.put("02-records-business-exact", runScenario("存证清单按单据号精确检索",
                request(RECORDS).param("businessNo", "DLBENCH000001"), 1L));
        results.put("03-records-status-page", runScenario("存证清单按状态过滤（FAILED 100 条）",
                request(RECORDS).param("status", "FAILED")
                        .param("pageNum", "1").param("pageSize", "100"), 100L));
        results.put("04-audit-anomalies", runScenario("审计异常清单分页（PENDING 300 + FAILED 100）",
                request(ANOMALIES).param("pageNum", "1").param("pageSize", "100"), 400L));
        results.put("05-audit-overview", runScenario("审计总览四环节矩阵聚合（万级全量）",
                request(AUDIT_OVERVIEW), null));

        writeResultJson(results);
        results.values().forEach(result -> assertThat(result.p95Ms())
                .as("场景[%s] P95 ≤ %dms（容器内口径）", result.label(), P95_BUDGET_MS)
                .isLessThanOrEqualTo(P95_BUDGET_MS));
    }

    /**
     * 造数：直插 1 万条（四环节轮转；每 100 条 1 条 FAILED、每 25 条 1 条 PENDING，其余 ON_CHAIN）。
     * 多值单语句分批（200 行/批）：容器 JDBC URL 未开 {@code rewriteBatchedStatements}，
     * 逐条 batch 在万级造数上耗时分钟级，会挤占全仓门禁 unitTest 预算。
     */
    private void seedTenThousandRows() {
        final String header = "INSERT INTO evidence_record (evidence_no, stage_type, business_no, "
                + "operation_type, subject_did_digest, occurred_at, content_digest, status, "
                + "chain_tx_id, chain_block_no, retry_count, created_at, updated_at) VALUES ";
        final String row = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
        final String didDigest = "a".repeat(64);
        final int batchSize = 200;
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>(batchSize * 13);
        for (int i = 1; i <= ROW_TOTAL; i++) {
            if (sql.length() == 0) {
                sql.append(header);
            } else {
                sql.append(", ");
            }
            sql.append(row);
            final String stage = STAGES.get((i - 1) % STAGES.size());
            final String status = (i % 100 == 0) ? "FAILED" : (i % 25 == 0 ? "PENDING" : "ON_CHAIN");
            final boolean onChain = "ON_CHAIN".equals(status);
            params.add(String.format("EV20261111%06d", i));
            params.add(stage);
            params.add(String.format("DLBENCH%06d", i));
            params.add(operationOf(stage));
            params.add(didDigest);
            params.add("2026-11-05 05:30:00.000");
            params.add(String.format("%064d", i));
            params.add(status);
            params.add(onChain ? "LTXBENCH" + String.format("%09d", i) : null);
            params.add(onChain ? (long) i : null);
            params.add("2026-11-05 05:30:00.000");
            params.add("2026-11-05 05:30:00.000");
            if (i % batchSize == 0) {
                jdbc.update(sql.toString(), params.toArray());
                sql.setLength(0);
                params.clear();
            }
        }
        if (sql.length() > 0) {
            jdbc.update(sql.toString(), params.toArray());
        }
    }

    private static String operationOf(final String stageType) {
        return switch (stageType) {
            case "AUTHORIZATION" -> "SPACE_MEMBER_ADMITTED";
            case "CONTRACT" -> "CONTRACT_EFFECTIVE";
            case "DELIVERY" -> "DELIVERY_CREATED";
            case "USAGE" -> "USAGE_ALLOWED";
            default -> throw new IllegalStateException("未知环节: " + stageType);
        };
    }

    private ScenarioResult runScenario(final String label, final MockHttpServletRequestBuilder builder,
            final Long expectedTotal) throws Exception {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            final MvcResult warmup = mockMvc.perform(builder).andReturn();
            assertThat(warmup.getResponse().getStatus()).as("场景[%s] 预热请求成功", label)
                    .isEqualTo(200);
        }
        final List<Long> samples = new ArrayList<>(ROUNDS);
        long totalRows = -1;
        for (int i = 0; i < ROUNDS; i++) {
            final long startedAt = System.nanoTime();
            final MvcResult result = mockMvc.perform(builder).andReturn();
            final long finishedAt = System.nanoTime();
            assertThat(result.getResponse().getStatus()).as("场景[%s] 请求成功", label).isEqualTo(200);
            final JsonNode data = MAPPER.readTree(
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
            totalRows = expectedTotal == null ? matrixTotal(data.path("matrix"))
                    : data.path("total").asLong();
            samples.add((finishedAt - startedAt) / 1_000_000L);
        }
        if (expectedTotal != null) {
            assertThat(totalRows).as("场景[%s] 命中行数符合造数分布", label)
                    .isEqualTo(expectedTotal);
        } else {
            assertThat(totalRows).as("场景[%s] 四环节矩阵计数合计 = 造数总量", label)
                    .isEqualTo(ROW_TOTAL);
        }
        final long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return new ScenarioResult(label,
                sorted[(int) Math.ceil(ROUNDS * 0.50) - 1],
                sorted[(int) Math.ceil(ROUNDS * 0.95) - 1],
                sorted[ROUNDS - 1], totalRows);
    }

    private static long matrixTotal(final JsonNode matrix) {
        long total = 0;
        for (final String stage : STAGES) {
            final JsonNode cell = matrix.path(stage);
            total += cell.path("PENDING").asLong() + cell.path("ON_CHAIN").asLong()
                    + cell.path("FAILED").asLong();
        }
        return total;
    }

    /**
     * 结果 JSON 落 target/benchmark/evidence-audit.json（target 不入库）；数字摘要随开发日志留痕。
     * 不写 stdout（AGENTS §4 禁止提交调试输出）。
     */
    private void writeResultJson(final Map<String, ScenarioResult> results) throws IOException {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("card", "WBS-3.8.6");
        root.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.putObject("dataset")
                .put("records", ROW_TOTAL)
                .put("stages", STAGES.size())
                .put("distribution", "每 100 条 1 条 FAILED / 每 25 条 1 条 PENDING / 其余 ON_CHAIN")
                .put("seed", "测试内直插（多值单语句分批 200 行），非真实业务链路写入");
        root.put("roundsPerScenario", ROUNDS);
        root.put("warmupRounds", WARMUP_ROUNDS);
        final boolean allWithinBudget = results.values().stream()
                .allMatch(result -> result.p95Ms() <= P95_BUDGET_MS);
        root.putObject("threshold")
                .put("metric", "P95").put("unit", "ms").put("budget", P95_BUDGET_MS)
                .put("verdict", allWithinBudget ? "PASS" : "FAIL");
        root.putObject("boundary")
                .put("channel", "MockMvc 进程内全链（无网络与网关）")
                .put("load", "串行采样，无并发加压——正式并发压测与阶梯加压归 WBS-4.2.1")
                .put("warmup", "每场景 5 轮预热后采样 20 轮（P95 取最近邻秩）");
        final ArrayNode scenarios = root.putArray("scenarios");
        results.values().forEach(result -> {
            final ObjectNode node = scenarios.addObject();
            node.put("name", result.label())
                    .put("p50Ms", result.p50Ms())
                    .put("p95Ms", result.p95Ms())
                    .put("maxMs", result.maxMs())
                    .put("totalRows", result.totalRows());
        });
        final Path dir = Path.of("target", "benchmark");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("evidence-audit.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder request(final String path) {
        return get(path).header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin");
    }

    /** 单场景结果（毫秒分位 + 命中行数；分位取最近邻秩）。 */
    private record ScenarioResult(String label, long p50Ms, long p95Ms, long maxMs, long totalRows) {
    }
}
