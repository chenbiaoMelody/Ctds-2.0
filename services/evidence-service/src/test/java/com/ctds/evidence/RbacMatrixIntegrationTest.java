package com.ctds.evidence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.6 T2（hifi §1）：RBAC 三员矩阵代码化——4 端点 × {platform.sysadmin,
 * platform.secadmin, platform.audadmin, 未认证} = 16 格轻断言矩阵（仅状态码 200/403/401）。
 * 留痕源 = 3.9.5 真机 14 格矩阵（precheck-summary.md:66-79）；口径差（真机含 requester
 * 负向格与 domain 行、未认证行真机未发）经 hifi §9 勘误登记。三员互斥判定：grant+revoke
 * 仅 secadmin；roles 仅 sysadmin；changes 仅 audadmin；未认证全 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class RbacMatrixIntegrationTest {

    private static final String GRANT_PATH = "/api/v1/rbac/permissions/grant";
    private static final String REVOKE_PATH = "/api/v1/rbac/permissions/revoke";
    private static final String ROLES_PATH = "/api/v1/rbac/roles";
    private static final String CHANGES_PATH = "/api/v1/rbac/changes";
    private static final String SYSADMIN = "platform.sysadmin";
    private static final String SECADMIN = "platform.secadmin";
    private static final String AUDADMIN = "platform.audadmin";
    private static final String SUBJECT = "S-W396-T2";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_rbac_matrix_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_rbac_matrix_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        registry.add("ctds.domain.schema-list", () ->
                "ctds_evidence_rbac_matrix_it,ctds_subject,ctds_space");
        registry.add("ctds.domain.service-list", () ->
                "evidence-service,subject-service,space-service,gateway-service");
    }

    @Autowired
    private MockMvc mockMvc;

    private String body(String order) {
        return "{\"role\":\"reviewer\",\"permission\":\"billing.read\",\"changeOrderNo\":\"" + order + "\"}";
    }

    /** grant 列 4 格：secadmin 200（写面生效），sysadmin/audadmin 403，未认证 401。 */
    @Test
    void grantColumnMatrix() throws Exception {
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-GRANT-SEC")))
                .andExpect(status().isOk());
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-GRANT-SYS")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", AUDADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-GRANT-AUD")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(GRANT_PATH)
                        .contentType("application/json").content(body("CHG-W396-T2-GRANT-ANON")))
                .andExpect(status().isUnauthorized());
    }

    /** revoke 列 4 格：secadmin 200（先授后收，收回成功腿），其余三格 403/401。 */
    @Test
    void revokeColumnMatrix() throws Exception {
        // 收回成功腿需要点在授予态：先授（同格 200 口径），再收回断言 200
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-GRANT-SEC-RV")))
                .andExpect(status().isOk());
        mockMvc.perform(post(REVOKE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-REVOKE-SEC-OK")))
                .andExpect(status().isOk());
        mockMvc.perform(post(REVOKE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-REVOKE-SYS")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REVOKE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", AUDADMIN)
                        .contentType("application/json").content(body("CHG-W396-T2-REVOKE-AUD")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REVOKE_PATH)
                        .contentType("application/json").content(body("CHG-W396-T2-REVOKE-ANON")))
                .andExpect(status().isUnauthorized());
    }

    /** roles 列 4 格：仅 sysadmin 200。 */
    @Test
    void rolesColumnMatrix() throws Exception {
        mockMvc.perform(get(ROLES_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk());
        mockMvc.perform(get(ROLES_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ROLES_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", AUDADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ROLES_PATH))
                .andExpect(status().isUnauthorized());
    }

    /** changes 列 4 格：仅 audadmin 200。 */
    @Test
    void changesColumnMatrix() throws Exception {
        mockMvc.perform(get(CHANGES_PATH + "?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", AUDADMIN))
                .andExpect(status().isOk());
        mockMvc.perform(get(CHANGES_PATH + "?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(CHANGES_PATH + "?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(CHANGES_PATH + "?pageNum=1&pageSize=10"))
                .andExpect(status().isUnauthorized());
    }
}
