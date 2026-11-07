package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.FlakyChainGatewayConfiguration;
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
 * 监管审计总览与异常项（WBS-3.8.4 hifi §8 T1 / T2 / T3；规格 B5-2① / B5-2②；剧本 S1-1）：
 * ① 四环节 × 三状态矩阵计数与 snapshotAt 可读；② 异常三计数（待上链 / 上链失败 / 核验不一致）；
 * ③ 异常清单三类齐备、类型标记正确、MISMATCH 行附最新核验结论与核验人时点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, FlakyChainGatewayConfiguration.class})
class EvidenceAuditOverviewIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String AUDIT_OVERVIEW = "/api/v1/evidence/audit/overview";
    private static final String AUDIT_ANOMALIES = "/api/v1/evidence/audit/anomalies";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_audit_overview_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(false);
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
        jdbc.update("DELETE FROM evidence_access_log");
        jdbc.update("DELETE FROM evidence_audit_report");
        jdbc.update("DELETE FROM evidence_report_no_seq");
    }

    private JsonNode create(final String stageType, final String businessNo,
            final String operationType) throws Exception {
        final String body = "{\"stageType\":\"" + stageType + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operationType + "\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T05:30:00Z\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(final String path) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 直插 PENDING 记录（待上链为崩溃窗口态，写面无法稳定构造；仅测试夹具直插）。 */
    private void insertPendingRecord(final String businessNo) {
        jdbc.update("INSERT INTO evidence_record (evidence_no, stage_type, business_no, "
                        + "operation_type, subject_did_digest, occurred_at, content_digest, status, "
                        + "retry_count, created_at, updated_at) VALUES (?, 'USAGE', ?, "
                        + "'USAGE_ALLOWED', ?, '2026-11-05 05:30:00.000', ?, 'PENDING', 0, "
                        + "'2026-11-05 05:30:00.000', '2026-11-05 05:30:00.000')",
                "EV20261105900001", businessNo, "pending-digest-0001", "pending-content-0001");
    }

    /** T1 / B5-2① / S1-1：四环节各 1 条 → 矩阵计数正确 + snapshotAt 可读。 */
    @Test
    void overviewMatrixCountsPerStage() throws Exception {
        create("AUTHORIZATION", "SPACE1:ADMISSION5", "SPACE_MEMBER_ADMITTED");
        create("CONTRACT", "CO000123", "CONTRACT_EFFECTIVE");
        create("DELIVERY", "DL000123", "DELIVERY_CREATED");
        create("USAGE", "USG000000000123", "USAGE_ALLOWED");

        final JsonNode data = getJson(AUDIT_OVERVIEW).path("data");
        final JsonNode matrix = data.path("matrix");
        for (final String stage : new String[] {"AUTHORIZATION", "CONTRACT", "DELIVERY", "USAGE"}) {
            assertThat(matrix.path(stage).path("ON_CHAIN").asLong())
                    .as("环节 %s 已上链计数", stage).isEqualTo(1L);
            assertThat(matrix.path(stage).path("PENDING").asLong())
                    .as("环节 %s 待上链计数", stage).isZero();
            assertThat(matrix.path(stage).path("FAILED").asLong())
                    .as("环节 %s 上链失败计数", stage).isZero();
        }
        assertThat(data.path("snapshotAt").asText()).as("快照时点可读").isNotBlank();
    }

    /** T2 / B5-2② / S1-1：构造 FAILED + MISMATCH → 异常三计数正确。 */
    @Test
    void overviewAnomalyCounts() throws Exception {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(true);
        create("DELIVERY", "DL000201", "DELIVERY_CREATED");
        final JsonNode attested = create("CONTRACT", "CO000201", "CONTRACT_EFFECTIVE")
                .path("data");

        jdbc.update("UPDATE evidence_chain_block SET payload_digest = ?",
                "tampered-digest-tampered-digest-tampered-digest-tampered");
        mockMvc.perform(post("/api/v1/evidence/records/" + attested.path("evidenceNo").asText()
                                + "/verify")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk());

        insertPendingRecord("USG000000000201");

        final JsonNode anomalies = getJson(AUDIT_OVERVIEW).path("data").path("anomalies");
        assertThat(anomalies.path("failedCount").asLong()).isEqualTo(1L);
        assertThat(anomalies.path("pendingCount").asLong()).isEqualTo(1L);
        assertThat(anomalies.path("mismatchCount").asLong()).as("核验不一致跨笔计数").isEqualTo(1L);
    }

    /** T3 / B5-2②：异常清单三类齐备、类型标记正确，MISMATCH 行附最新核验结论与核验人。 */
    @Test
    void anomalyListCoversThreeTypes() throws Exception {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(true);
        create("DELIVERY", "DL000301", "DELIVERY_CREATED");
        final JsonNode attested = create("CONTRACT", "CO000301", "CONTRACT_EFFECTIVE")
                .path("data");
        insertPendingRecord("USG000000000301");

        jdbc.update("UPDATE evidence_chain_block SET payload_digest = ?",
                "tampered-digest-tampered-digest-tampered-digest-tampered");
        mockMvc.perform(post("/api/v1/evidence/records/" + attested.path("evidenceNo").asText()
                                + "/verify")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk());

        final JsonNode data = getJson(AUDIT_ANOMALIES + "?pageSize=10").path("data");
        assertThat(data.path("total").asLong()).as("三类异常各 1 条").isEqualTo(3L);
        final JsonNode list = data.path("list");
        final var types = new java.util.HashSet<String>();
        for (final JsonNode item : list) {
            types.add(item.path("anomalyType").asText());
            if ("MISMATCH".equals(item.path("anomalyType").asText())) {
                assertThat(item.path("verifyConclusion").asText()).isEqualTo("MISMATCH");
                assertThat(item.path("verifiedBy").asText()).isEqualTo(ADMIN);
                assertThat(item.path("verifiedAt").asText()).isNotBlank();
            }
        }
        assertThat(types).as("三类类型标记齐备")
                .containsExactlyInAnyOrder("PENDING", "FAILED", "MISMATCH");
    }
}
