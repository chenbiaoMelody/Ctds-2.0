package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.3 T5/T6（B9-R6 退役双向，evidence 全链路腿）：`ctds.domain.status=RETIRED` 时——
 * ① 业务（内部写面）503 + `1013C0004` + "部署域已退役"；② 豁免面（审计读面 / 部署域读面
 * / actuator 类模式）照常可读；③ 数据分毫不动（拦截前后存证记录行数一致，B9-R6 数据保留）；
 * ④ 读面在退役态照常返回 lifecycleStatus=RETIRED（S3-5 读面复核）。
 * 豁免模式**不注入**——走 evidence application.yml 主配置缺省值（评审① P1-1 修复回归：
 * 主配置下退役态豁免面成立，非测试注入产物）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false",
                "ctds.domain.status=RETIRED"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DomainRetiredIntegrationTest {

    private static final String INTERNAL_WRITE = "/api/v1/evidence/internal/records";
    private static final String AUDIT_OVERVIEW = "/api/v1/evidence/audit/overview";
    private static final String DOMAIN_PATH = "/api/v1/platform/domain";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_domain_retired_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_domain_retired_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        registry.add("ctds.domain.schema-list", () ->
                "ctds_evidence_domain_retired_it,ctds_subject,ctds_space");
        registry.add("ctds.domain.service-list", () ->
                "evidence-service,subject-service,space-service,gateway-service");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedOneRecord() {
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
        jdbc.update("INSERT INTO evidence_record (evidence_no, stage_type, business_no, "
                        + "operation_type, subject_did_digest, occurred_at, content_digest, "
                        + "status, created_at, updated_at) VALUES "
                        + "('EV20261114000001', 'DELIVERY', 'DEMO-RETAIN-1', 'DELIVERY_CREATED', "
                        + "'a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8', "
                        + "'2026-11-14 10:00:00.000', "
                        + "'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', "
                        + "'ON_CHAIN', '2026-11-14 10:00:00.000', '2026-11-14 10:00:00.000')");
    }

    @Test
    void retiredBlocksBusinessWriteWith503AndReservedCode() throws Exception {
        mockMvc.perform(post(INTERNAL_WRITE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stageType\":\"DELIVERY\",\"businessNo\":\"BLOCKED-1\","
                                + "\"operationType\":\"DELIVERY_CREATED\","
                                + "\"subjectDid\":\"did:ctds:x\",\"occurredAt\":"
                                + "\"2026-11-14T10:00:00Z\",\"actorSubjectNo\":\"S1\"}")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1013C0004"))
                .andExpect(jsonPath("$.message").value("部署域已退役"));
    }

    @Test
    void retiredKeepsAuditReadAndDomainReadFacesAvailable() throws Exception {
        mockMvc.perform(get(AUDIT_OVERVIEW)
                        .header("X-Ctds-Subject", "S-aud-domain")
                        .header("X-Ctds-Roles", "platform.audadmin"))
                .andExpect(status().isOk());
        mockMvc.perform(get(DOMAIN_PATH)
                        .header("X-Ctds-Subject", "S-admin-domain")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycleStatus").value("RETIRED"));
    }

    @Test
    void retiredLeavesDataUntouchedAfterBlockedAttempts() throws Exception {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_record", Long.class);
        assertThat(before).isEqualTo(1L);
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post(INTERNAL_WRITE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"stageType\":\"DELIVERY\",\"businessNo\":\"BLOCKED-" + attempt
                                    + "\",\"operationType\":\"DELIVERY_CREATED\","
                                    + "\"subjectDid\":\"did:ctds:x\",\"occurredAt\":"
                                    + "\"2026-11-14T10:00:00Z\",\"actorSubjectNo\":\"S1\"}")
                            .header("X-Ctds-Subject", "delivery-service")
                            .header("X-Ctds-Roles", "evidence-internal"))
                    .andExpect(status().isServiceUnavailable());
        }
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_record", Long.class);
        assertThat(after).as("退役态数据保留：拦截后行数不变").isEqualTo(before);
    }
}
