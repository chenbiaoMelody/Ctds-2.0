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
 * 审计读面越权同码同文（WBS-3.8.4 hifi §8 T12；规格 B5-4 / B8-1；剧本 S1-7 / S2-5）：
 * 非治理档访问审计读面 ↔ 治理档查询不存在对象，两者出站 **code / message / HTTP 状态三逐字一致**
 * （400 + 1000C0003 + "记录不存在"，防存在性枚举；ADR-016 §2.6 先例同型）。
 * 3.8.2 既有治理端点 403 口径不在此断言面（沿 EvidenceGovernanceIntegrationTest）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceAuditGuardIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUDIT_TRAILS = "/api/v1/evidence/audit/trails";
    private static final String ADMIN = "S-admin-evidence";
    private static final int EXPECTED_STATUS = 400;
    private static final String EXPECTED_CODE = "1000C0003";
    private static final String EXPECTED_MESSAGE = "记录不存在";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_audit_guard_it");
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

    private JsonNode bodyOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T12 / S1-7：越权 ↔ 不存在，三逐字一致（trails 双向 + reports + export + overview 全端点覆盖）。 */
    @Test
    void unauthorizedAndNonexistentShareExactOutbound() throws Exception {
        // 参照：治理档查不存在的单据号（"对象不存在"出站）
        final MvcResult nonexistent = mockMvc.perform(
                        get(AUDIT_TRAILS + "?businessNo=NOPE999999")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().is(EXPECTED_STATUS)).andReturn();
        // 越权：非治理档查一个真实存在范围的条件（守卫先拒）
        final MvcResult unauthorized = mockMvc.perform(
                        get(AUDIT_TRAILS + "?stageType=DELIVERY")
                                .header("X-Ctds-Subject", "S-req-evidence")
                                .header("X-Ctds-Roles", "requester"))
                .andExpect(status().is(EXPECTED_STATUS)).andReturn();

        final JsonNode nonexistentBody = bodyOf(nonexistent);
        final JsonNode unauthorizedBody = bodyOf(unauthorized);
        assertThat(unauthorizedBody.path("code").asText())
                .as("同码").isEqualTo(nonexistentBody.path("code").asText()).isEqualTo(EXPECTED_CODE);
        assertThat(unauthorizedBody.path("message").asText())
                .as("同文防枚举").isEqualTo(nonexistentBody.path("message").asText())
                .isEqualTo(EXPECTED_MESSAGE);

        // 其余审计端点越权出站同码同文（overview / anomalies / reports / export）
        final String[] getPaths = {
            "/api/v1/evidence/audit/overview",
            AUDIT_ANOMALIES_PATH,
            AUDIT_TRAILS + "?stageType=DELIVERY",
            "/api/v1/evidence/audit/reports/EV-RPT-20261105-0001/export?format=json",
        };
        for (final String path : getPaths) {
            final MvcResult result = mockMvc.perform(get(path)
                            .header("X-Ctds-Subject", "S-req-evidence")
                            .header("X-Ctds-Roles", "requester"))
                    .andExpect(status().is(EXPECTED_STATUS)).andReturn();
            final JsonNode body = bodyOf(result);
            assertThat(body.path("code").asText()).as("端点 %s 同码", path).isEqualTo(EXPECTED_CODE);
            assertThat(body.path("message").asText()).as("端点 %s 同文", path)
                    .isEqualTo(EXPECTED_MESSAGE);
        }
        final MvcResult reportPost = mockMvc.perform(
                        post("/api/v1/evidence/audit/reports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"stageType\":\"DELIVERY\"}")
                                .header("X-Ctds-Subject", "S-req-evidence")
                                .header("X-Ctds-Roles", "requester"))
                .andExpect(status().is(EXPECTED_STATUS)).andReturn();
        assertThat(bodyOf(reportPost).path("code").asText()).isEqualTo(EXPECTED_CODE);
        assertThat(bodyOf(reportPost).path("message").asText()).isEqualTo(EXPECTED_MESSAGE);
    }

    private static final String AUDIT_ANOMALIES_PATH = "/api/v1/evidence/audit/anomalies";
}
