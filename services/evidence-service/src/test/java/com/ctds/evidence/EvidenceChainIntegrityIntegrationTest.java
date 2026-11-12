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
 * 本地账本完整性（WBS-3.8.6 hifi §1 T7；规格 B3-1 / B3-2）：
 * 核验对"账本缺块"（{@code evidence_chain_block} 行被删）必须判定 **MISMATCH**——
 * 不得伪报 PASS、不得回退成 NOT_ON_CHAIN；且该结论在审计读面计入异常（{@code mismatchCount} +1）。
 *
 * <p>与既有 {@code EvidenceVerifyIntegrationTest.verifyDetectsLedgerTamperAndRestores}（改摘要）互补：
 * 本类覆盖"整块缺失"这一独立篡改形态（hifi §7 P4 实测缺口）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceChainIntegrityIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String AUDIT_OVERVIEW = "/api/v1/evidence/audit/overview";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_chain_integrity_it");
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

    /** T7：删块 → 核验 MISMATCH（无账本摘要）+ 审计异常计数 mismatchCount +1。 */
    @Test
    void deletedLedgerBlockReportsMismatchAndCountsAsAnomaly() throws Exception {
        final JsonNode created = create("DL000721");
        final String evidenceNo = created.path("evidenceNo").asText();
        assertThat(created.path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .isEqualTo(1L);

        jdbc.update("DELETE FROM evidence_chain_block");

        final JsonNode verify = verify(evidenceNo);
        assertThat(verify.path("conclusion").asText())
                .as("账本缺块必须判 MISMATCH（不伪报 PASS / 不回退 NOT_ON_CHAIN）")
                .isEqualTo("MISMATCH");
        assertThat(verify.path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(verify.path("actualDigest").isNull()).as("缺块时无账本摘要").isTrue();
        assertThat(verify.path("expectedDigest").asText()).hasSize(64);
        assertThat(verify.path("localDigest").asText()).hasSize(64);
        assertThat(verify.path("expectedDigest").asText())
                .as("重算摘要与本地摘要一致，差异定位在账本缺失")
                .isEqualTo(verify.path("localDigest").asText());

        final JsonNode anomalies = getJson(AUDIT_OVERVIEW).path("data").path("anomalies");
        assertThat(anomalies.path("mismatchCount").asLong())
                .as("核验不一致计入审计异常").isEqualTo(1L);
        assertThat(anomalies.path("pendingCount").asLong()).as("状态未变不回退为待上链").isZero();
        assertThat(anomalies.path("failedCount").asLong()).isZero();
    }

    private JsonNode create(final String businessNo) throws Exception {
        final String body = "{\"stageType\":\"DELIVERY\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"DELIVERY_CREATED\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T05:30:00Z\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private JsonNode verify(final String evidenceNo) throws Exception {
        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/verify")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private JsonNode getJson(final String path) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
