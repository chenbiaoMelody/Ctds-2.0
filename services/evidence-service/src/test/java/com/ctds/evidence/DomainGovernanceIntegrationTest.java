package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.3 T1/T2（B9-1 读面双向）：sysadmin 经 V4 库表授予可读六字段 + ACTIVE + CONSISTENT
 * + scopeNote；业务角色（admin，无 platform.domain.read）403 + `1000C0005`（B3-2 同码）；
 * 未认证 401 + `1000C0002`（ADR-005 :37）。映射来源 = 库表 seed（jdbc source，V4 端到端验证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DomainGovernanceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN_PATH = "/api/v1/platform/domain";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_domain_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_domain_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        registry.add("ctds.domain.schema-list", () ->
                "ctds_evidence_domain_it,ctds_subject,ctds_space");
        registry.add("ctds.domain.service-list", () ->
                "evidence-service,subject-service,space-service,gateway-service");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sysadminReadsSixFieldsWithConsistencyVerdict() throws Exception {
        MvcResult result = mockMvc.perform(get(DOMAIN_PATH)
                        .header("X-Ctds-Subject", "S-admin-domain")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        JsonNode data = MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("domainId").asText()).isEqualTo("ctds-demo-domain");
        assertThat(data.path("domainName").asText()).isEqualTo("演示部署域");
        assertThat(data.path("platformVersion").asText()).isEqualTo("V1.0");
        assertThat(data.path("lifecycleStatus").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("schemaList").toString()).contains("ctds_evidence_domain_it");
        assertThat(data.path("serviceList").toString()).contains("evidence-service");
        assertThat(data.path("configConsistency").asText()).isEqualTo("CONSISTENT");
        assertThat(data.path("scopeNote").asText()).contains("单实例单部署域");
    }

    @Test
    void businessRoleWithoutPointIsForbiddenWithCommonCode() throws Exception {
        mockMvc.perform(get(DOMAIN_PATH)
                        .header("X-Ctds-Subject", "S-ops-domain")
                        .header("X-Ctds-Roles", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void unauthenticatedIsUnauthorizedWithCommonCode() throws Exception {
        mockMvc.perform(get(DOMAIN_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }
}
