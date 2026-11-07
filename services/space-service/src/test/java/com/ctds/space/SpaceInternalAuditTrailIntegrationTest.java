package com.ctds.space;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.space.support.SharedMySqlContainer;
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
 * 空间域内部 audit-trail 只读端点（WBS-3.8.4 hifi §1 / T20；Q1-A 跨域留痕锚）：
 * spaceId + admissionId | actionLogId 二选一 → 准入单 / 留痕行白名单字段（subjectNo 原文由
 * evidence 聚合层摘要化，此处原样返回，出站面在 evidence 侧）；权限 = evidence-internal 服务身份
 * 专用（业务角色不持有 space.internal.read，403 防枚举沿 InternalMembership 先例）；
 * 锚点不存在 → 400 + 1000C0003（与 evidence 审计读面出站同码，全局默认映射，零改域内码表）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceInternalAuditTrailIntegrationTest {

    private static final String AUDIT_TRAILS = "/api/v1/data-spaces/internal/audit-trails";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_audit_it");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-space-trail");
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
        jdbc.update("DELETE FROM space_action_log");
        jdbc.update("DELETE FROM space_admission");
    }

    private MockHttpServletRequestBuilder evidenceInternal(final String query) {
        return get(AUDIT_TRAILS + query)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Ctds-Subject", "evidence-service")
                .header("X-Ctds-Roles", "evidence-internal");
    }

    private long insertAdmission() {
        jdbc.update("INSERT INTO space_admission (space_id, subject_no, type, status, operator) "
                + "VALUES (1, 'S20260922000001', 'APPLICATION', 'APPROVED', 'S20260922000001')");
        return jdbc.queryForObject("SELECT id FROM space_admission WHERE space_id = 1", Long.class);
    }

    private long insertActionLog(final String targetType, final long targetId) {
        jdbc.update("INSERT INTO space_action_log (space_id, target_type, target_id, action, "
                        + "operator, result) VALUES (1, ?, ?, 'ADMIT_APPROVE', 'S20260922000001', "
                        + "'SUCCESS')",
                targetType, targetId);
        return jdbc.queryForObject(
                "SELECT id FROM space_action_log WHERE target_type = ? AND target_id = ?",
                Long.class, targetType, targetId);
    }

    /** T20 / Q1-A：ADMISSION 锚点 → 准入单 + 该准入相关留痕白名单字段。 */
    @Test
    void admissionAnchorReturnsAdmissionAndLogs() throws Exception {
        final long admissionId = insertAdmission();
        insertActionLog("ADMISSION", admissionId);

        mockMvc.perform(evidenceInternal("?spaceId=1&admissionId=" + admissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.admission.id").value((int) admissionId))
                .andExpect(jsonPath("$.data.admission.subjectNo").value("S20260922000001"))
                .andExpect(jsonPath("$.data.admission.type").value("APPLICATION"))
                .andExpect(jsonPath("$.data.admission.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.admission.operator").value("S20260922000001"))
                .andExpect(jsonPath("$.data.actionLogs").isArray())
                .andExpect(jsonPath("$.data.actionLogs[0].action").value("ADMIT_APPROVE"))
                .andExpect(jsonPath("$.data.actionLogs[0].result").value("SUCCESS"));
    }

    /** T20 / Q1-A：POLICY-AL 锚点（actionLogId）→ 留痕行白名单字段。 */
    @Test
    void actionLogAnchorReturnsLogRow() throws Exception {
        final long logId = insertActionLog("POLICY", 7);

        mockMvc.perform(evidenceInternal("?spaceId=1&actionLogId=" + logId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.actionLogs[0].id").value((int) logId))
                .andExpect(jsonPath("$.data.actionLogs[0].action").value("ADMIT_APPROVE"));
    }

    /** T20：锚点不存在 → 400 + 1000C0003；业务角色 → 403（防枚举收敛）。 */
    @Test
    void unknownAnchorAndUnauthorizedRejected() throws Exception {
        mockMvc.perform(evidenceInternal("?spaceId=1&admissionId=424242"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));

        final long admissionId = insertAdmission();
        mockMvc.perform(get(AUDIT_TRAILS + "?spaceId=1&admissionId=" + admissionId)
                        .header("X-Ctds-Subject", "S-member").header("X-Ctds-Roles", "member"))
                .andExpect(status().isForbidden());
    }
}
