package com.ctds.evidence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * WBS-3.9.3 边界 7（hifi §4；评审① P2-1 修复）：未配 `ctds.domain.schema-list` 时——
 * 启动校验跳过（有数据源但清单为空）、读面 `configConsistency` = `NOT_APPLICABLE`
 * （诚实标注，不显示"正常"；B9-R4/Q14-A 诚实口径护栏）。读面其余字段照常渲染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DomainReadFaceNotApplicableIntegrationTest {

    private static final String DOMAIN_PATH = "/api/v1/platform/domain";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_domain_na_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_domain_na_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        // 故意不配 ctds.domain.schema-list / service-list（边界 7 被测态）
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readFaceHonestNotApplicableWhenSchemaListUnset() throws Exception {
        mockMvc.perform(get(DOMAIN_PATH)
                        .header("X-Ctds-Subject", "S-admin-na")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configConsistency").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.lifecycleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.domainId").value("ctds-demo-domain"));
    }
}
