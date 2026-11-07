package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 审计报告生成与下载（WBS-3.8.4 hifi §8 T13~T18；规格 B7-1~B7-4 / B6-4；剧本 S2-1~S2-7）：
 * 报告编号唯一、要素齐备、内容零原文零个人信息（负向扫描）、同条件重复生成新编号不覆盖、
 * 下载 = 登记快照原样输出（CSV/JSON）+ 导出留痕、篡改库内容 → 1012S0003 且不输出文件、
 * 无条件 / 宽窗 / 超过 10000 条 → 1012C0002。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceAuditReportIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String REPORTS = "/api/v1/evidence/audit/reports";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_audit_report_it");
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

    private void create(final String stageType, final String businessNo,
            final String operationType) throws Exception {
        final String body = "{\"stageType\":\"" + stageType + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operationType + "\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T05:30:00Z\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk());
    }

    private JsonNode generateReport(final String body, final int expectedStatus) throws Exception {
        final MvcResult result = mockMvc.perform(post(REPORTS)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T13 / B7-1 / B6-4 / S2-1·S2-6：报告要素齐备 + 编号唯一 + 内容零原文零个人信息。 */
    @Test
    void reportGenerationHasCompleteElementsAndNoRawData() throws Exception {
        create("DELIVERY", "DL000501", "DELIVERY_CREATED");
        create("DELIVERY", "DL000502", "DELIVERY_CREATED");

        final JsonNode body = generateReport(
                "{\"stageType\":\"DELIVERY\",\"remark\":\"季度合规审计\"}", 200).path("data");
        assertThat(body.path("reportNo").asText())
                .as("编号形态 EV-RPT-yyyymmdd-xxxx（固定时钟 UTC 业务日）")
                .matches("EV-RPT-20261105-\\d{4}");
        assertThat(body.path("recordCount").asInt()).isEqualTo(2);
        assertThat(body.path("contentDigest").asText()).as("SM3 内容摘要随行").hasSize(64);
        assertThat(body.path("conclusionSummary").path("NOT_VERIFIED").asInt())
                .as("记录未核验 → 计入 NOT_VERIFIED（分布按核验留痕统计）").isEqualTo(2);
        assertThat(body.path("conclusionSummary").path("PASS").asInt()).isZero();
        assertThat(body.path("anomalyCount").asInt()).isEqualTo(0);
        assertThat(body.path("generatedBy").asText()).isEqualTo(ADMIN);
        assertThat(body.path("generatedAt").asText()).isNotBlank();
        assertThat(body.path("remark").asText()).isEqualTo("季度合规审计");

        final String content = jdbc.queryForObject(
                "SELECT report_content_json FROM evidence_audit_report", String.class);
        assertThat(content).as("快照内容零原文零个人信息（负向扫描）")
                .doesNotContain("did:ctds:")
                .doesNotContain("provider-0001")
                .doesNotContain("S2026");
    }

    /** T14 / B7-1 / S2-2：同条件报告条数与结论分布 = 视图读数（同快照对照）。 */
    @Test
    void reportCountsMatchViewReadings() throws Exception {
        create("DELIVERY", "DL000601", "DELIVERY_CREATED");
        create("DELIVERY", "DL000602", "DELIVERY_CREATED");

        final JsonNode report = generateReport("{\"stageType\":\"DELIVERY\"}", 200).path("data");
        final MvcResult trails = mockMvc.perform(
                        get("/api/v1/evidence/audit/trails?stageType=DELIVERY&pageNum=1&pageSize=10")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk()).andReturn();
        final JsonNode trailsData = MAPPER.readTree(
                trails.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(report.path("recordCount").asInt())
                .as("报告条数 = 视图清单读数").isEqualTo(trailsData.path("total").asInt());

        final Long dbOnChain = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_record WHERE status = 'ON_CHAIN'", Long.class);
        assertThat(dbOnChain).isEqualTo(2L);
        assertThat(report.path("conclusionSummary").path("NOT_VERIFIED").asInt())
                .as("结论分布按核验留痕统计：已上链但未核验 → NOT_VERIFIED").isEqualTo(2);
        assertThat(report.path("conclusionSummary").path("PASS").asInt()).isZero();
    }

    /** T15 / B7-2 / S2-3：重复生成 = 新编号不覆盖，两份均可读。 */
    @Test
    void repeatedGenerationCreatesNewReportNo() throws Exception {
        create("DELIVERY", "DL000701", "DELIVERY_CREATED");
        final JsonNode first = generateReport("{\"stageType\":\"DELIVERY\"}", 200).path("data");
        final JsonNode second = generateReport("{\"stageType\":\"DELIVERY\"}", 200).path("data");

        assertThat(second.path("reportNo").asText())
                .as("新编号不覆盖").isNotEqualTo(first.path("reportNo").asText());
        final Long rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_audit_report", Long.class);
        assertThat(rows).isEqualTo(2L);
        final String firstContent = jdbc.queryForObject(
                "SELECT report_content_json FROM evidence_audit_report WHERE report_no = ?",
                String.class, first.path("reportNo").asText());
        assertThat(firstContent).as("首份报告仍可读").isNotBlank();
    }

    /** T16 / B7-3·B7-4 / S2-4：CSV/JSON 快照原样输出 + 导出留痕 1 行。 */
    @Test
    void exportRendersSnapshotAndWritesAccessLog() throws Exception {
        create("DELIVERY", "DL000801", "DELIVERY_CREATED");
        final JsonNode report = generateReport("{\"stageType\":\"DELIVERY\"}", 200).path("data");
        final String reportNo = report.path("reportNo").asText();

        final MvcResult csv = mockMvc.perform(
                        get(REPORTS + "/" + reportNo + "/export?format=csv")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk()).andReturn();
        final String csvBody = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.getResponse().getContentType()).contains("text/csv");
        assertThat(csvBody).as("CSV 列白名单表头")
                .startsWith("evidenceNo,stageType,businessNo,operationType,subjectDidDigest,"
                        + "occurredAt,status,chainTxId,contentDigest,verifyConclusion,anomalyType");
        assertThat(csvBody).as("CSV 数据行含存证编号").contains("DL000801");
        assertThat(csvBody).doesNotContain("did:ctds:");

        final MvcResult json = mockMvc.perform(
                        get(REPORTS + "/" + reportNo + "/export?format=json")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk()).andReturn();
        final String jsonBody = json.getResponse().getContentAsString(StandardCharsets.UTF_8);
        final String stored = jdbc.queryForObject(
                "SELECT report_content_json FROM evidence_audit_report WHERE report_no = ?",
                String.class, reportNo);
        assertThat(jsonBody).as("JSON 导出 = 登记快照原样输出").isEqualTo(stored);

        final Long exports = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_access_log WHERE access_kind = 'REPORT_EXPORT'",
                Long.class);
        assertThat(exports).as("两次下载各落 1 条导出留痕").isEqualTo(2L);
    }

    /** T17 / B7-2 / S2-7：篡改库内报告内容 → 1012S0003 且不输出文件。 */
    @Test
    void tamperedReportFailsIntegrityAndOutputsNothing() throws Exception {
        create("DELIVERY", "DL000901", "DELIVERY_CREATED");
        final String reportNo = generateReport("{\"stageType\":\"DELIVERY\"}", 200)
                .path("data").path("reportNo").asText();

        jdbc.update("UPDATE evidence_audit_report SET report_content_json = ? WHERE report_no = ?",
                "{\"tampered\":true}", reportNo);

        final MvcResult result = mockMvc.perform(
                        get(REPORTS + "/" + reportNo + "/export?format=json")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isInternalServerError()).andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("1012S0003");
        assertThat(body.path("message").asText()).isEqualTo("报告内容完整性校验未通过");
        assertThat(result.getResponse().getContentType()).as("不输出文件，出站为错误封套")
                .contains("application/json");
    }

    /** T18 / B6-2：无条件 / 宽窗 / 超过 10000 条 → 1012C0002。 */
    @Test
    void reportLimitsRejected() throws Exception {
        final JsonNode noCondition = generateReport("{}", 400);
        assertThat(noCondition.path("code").asText()).isEqualTo("1012C0002");

        final JsonNode wideWindow = generateReport(
                "{\"from\":\"2026-08-01T00:00:00Z\",\"to\":\"2026-11-05T13:30:00Z\"}", 400);
        assertThat(wideWindow.path("code").asText()).isEqualTo("1012C0002");

        insertRecords(10_001);
        final JsonNode overLimit = generateReport("{\"stageType\":\"DELIVERY\"}", 400);
        assertThat(overLimit.path("code").asText()).isEqualTo("1012C0002");
        assertThat(overLimit.path("message").asText())
                .as("上限文案与契约逐字一致").isEqualTo("清单条数超过导出上限 10000");
    }

    /** 直插批量记录（>10000 上限构造；绕开写面链上流程，仅测试夹具）。 */
    private void insertRecords(final int count) {
        int inserted = 0;
        int batchNo = 0;
        while (inserted < count) {
            final int size = Math.min(500, count - inserted);
            final StringBuilder sql = new StringBuilder(
                    "INSERT INTO evidence_record (evidence_no, stage_type, business_no, "
                            + "operation_type, subject_did_digest, occurred_at, content_digest, "
                            + "status, retry_count, created_at, updated_at) VALUES ");
            for (int i = 0; i < size; i++) {
                final long seq = (long) batchNo * 500 + i;
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("('EVFIX").append(String.format("%012d", seq))
                        .append("','DELIVERY','DLFIX").append(String.format("%06d", seq % 1000000))
                        .append(seq / 1000000)
                        .append("','DELIVERY_CREATED','fixdigest").append(String.format("%016d", seq))
                        .append("','2026-11-05 05:30:00.000','fixcontent")
                        .append(String.format("%016d", seq))
                        .append("','ON_CHAIN',0,'2026-11-05 05:30:00.000',"
                                + "'2026-11-05 05:30:00.000')");
            }
            jdbc.update(sql.toString());
            inserted += size;
            batchNo++;
        }
    }
}
