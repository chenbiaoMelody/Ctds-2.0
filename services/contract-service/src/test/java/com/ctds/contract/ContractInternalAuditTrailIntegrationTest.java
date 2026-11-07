package com.ctds.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.contract.support.SharedMySqlContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 合约域内部 audit-trail 只读端点两条路径（WBS-3.8.4 hifi §1 / T20；Q1-A 跨域留痕锚）：
 * ① /internal/{contractNo}/audit-trail → 存证要素（attestation）+ 留痕白名单字段；
 * ② /internal/usage-logs/{usageLogId}/audit-trail → 使用放行留痕行；
 * 权限 = evidence-internal 服务身份专用（业务角色不持有 contract.internal.read → 403，
 * 沿 gateway-internal / delivery-internal 先例）；锚点不存在 → 400 + 1000C0003。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractInternalAuditTrailIntegrationTest {

    private static final String CONTRACT_TRAIL =
            "/api/v1/contracts/internal/CO000123/audit-trail";
    private static final String USAGE_TRAIL =
            "/api/v1/contracts/internal/usage-logs/%d/audit-trail";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_audit_it");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-contract-trail");
    }

    @AfterAll
    static void deleteAuditDir() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM contract_usage_log");
        jdbc.update("DELETE FROM contract_action_log");
        jdbc.update("DELETE FROM contract_attestation");
    }

    private MockHttpServletRequestBuilder evidenceInternal(final String path) {
        return get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Ctds-Subject", "evidence-service")
                .header("X-Ctds-Roles", "evidence-internal");
    }

    private void insertAttestation() {
        jdbc.update("INSERT INTO contract_attestation (contract_id, contract_no, content_hash, "
                        + "provider_subject_no, provider_did, provider_signed_at, "
                        + "requester_subject_no, requester_did, requester_signed_at) VALUES "
                        + "(1, 'CO000123', ?, 'S-provider', 'did:ctds:provider-0001', "
                        + "'2026-11-02 08:00:00', 'S-requester', 'did:ctds:requester-0001', "
                        + "'2026-11-02 08:01:00')",
                "a".repeat(64));
        jdbc.update("INSERT INTO contract_action_log (contract_no, action, actor_subject_no, "
                + "to_value) VALUES ('CO000123', 'ATTEST', 'S-provider', 'EFFECTIVE')");
    }

    private long insertUsageLog() {
        jdbc.update("INSERT INTO contract_usage_log (contract_no, requester_no, action_type, "
                        + "outcome, used_count, occurred_at) VALUES ('CO000123', 'S-requester', "
                        + "'USE', 'ALLOWED', 1, '2026-11-03 08:00:00')");
        return jdbc.queryForObject(
                "SELECT id FROM contract_usage_log WHERE contract_no = 'CO000123'", Long.class);
    }

    /** T20 / Q1-A：contractNo 锚点 → attestation + actionLogs 白名单字段。 */
    @Test
    void contractNoAnchorReturnsAttestationAndLogs() throws Exception {
        insertAttestation();
        mockMvc.perform(evidenceInternal(CONTRACT_TRAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.attestation.contractNo").value("CO000123"))
                .andExpect(jsonPath("$.data.attestation.providerDid").value("did:ctds:provider-0001"))
                .andExpect(jsonPath("$.data.attestation.requesterDid").value("did:ctds:requester-0001"))
                .andExpect(jsonPath("$.data.attestation.attestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.actionLogs").isArray())
                .andExpect(jsonPath("$.data.actionLogs[0].action").value("ATTEST"))
                .andExpect(jsonPath("$.data.actionLogs[0].actorSubjectNo").value("S-provider"));
    }

    /** T20 / Q1-A：usageLogId 锚点 → 使用放行留痕行白名单字段。 */
    @Test
    void usageLogIdAnchorReturnsUsageLog() throws Exception {
        final long usageLogId = insertUsageLog();
        mockMvc.perform(evidenceInternal(String.format(USAGE_TRAIL, usageLogId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.usageLog.contractNo").value("CO000123"))
                .andExpect(jsonPath("$.data.usageLog.actionType").value("USE"))
                .andExpect(jsonPath("$.data.usageLog.outcome").value("ALLOWED"))
                .andExpect(jsonPath("$.data.usageLog.usedCount").value(1))
                .andExpect(jsonPath("$.data.usageLog.occurredAt").isNotEmpty());
    }

    /** T20：锚点不存在 → 400 + 1000C0003；业务角色 → 403（防枚举收敛）。 */
    @Test
    void unknownAnchorAndUnauthorizedRejected() throws Exception {
        mockMvc.perform(evidenceInternal("/api/v1/contracts/internal/CO999999/audit-trail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));

        insertAttestation();
        mockMvc.perform(get(CONTRACT_TRAIL)
                        .header("X-Ctds-Subject", "S-provider").header("X-Ctds-Roles", "provider"))
                .andExpect(status().isForbidden());
    }
}
