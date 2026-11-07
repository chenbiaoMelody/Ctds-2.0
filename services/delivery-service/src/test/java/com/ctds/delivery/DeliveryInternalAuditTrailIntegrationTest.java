package com.ctds.delivery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.delivery.support.SharedMySqlContainer;
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
 * 交付域内部 audit-trail 只读端点（WBS-3.8.4 hifi §1 / T20；Q1-A 跨域留痕锚；delivery 首个内部端点）：
 * deliveryNo → 交付记录 + 留痕白名单字段；权限 = 新增权限点 delivery.internal.read
 * （仅 evidence-internal 服务身份持有，业务角色 → 403）；锚点不存在 → 400 + 1000C0003。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryInternalAuditTrailIntegrationTest {

    private static final String AUDIT_TRAIL = "/api/v1/deliveries/internal/DL000123/audit-trail";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_audit_it");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-delivery-trail");
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
        jdbc.update("DELETE FROM delivery_action_log");
        jdbc.update("DELETE FROM delivery");
    }

    private MockHttpServletRequestBuilder evidenceInternal(final String path) {
        return get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Ctds-Subject", "evidence-service")
                .header("X-Ctds-Roles", "evidence-internal");
    }

    private void insertDelivery() {
        jdbc.update("INSERT INTO delivery (delivery_no, contract_no, product_id, product_name, "
                        + "delivery_type, requester_no, provider_no, file_id, file_name, file_size, "
                        + "result, idempotency_key) VALUES ('DL000123', 'CO000123', 1, '模型数据集', "
                        + "'FILE', 'S-requester', 'S-provider', 'file-key-1', 'model-v1.zip', 1024, "
                        + "'SUCCESS', 'idem-audit-1')");
        jdbc.update("INSERT INTO delivery_action_log (delivery_no, contract_no, operator_no, "
                + "action, result) VALUES ('DL000123', 'CO000123', 'S-provider', "
                + "'DELIVERY_CREATED', 'SUCCESS')");
    }

    /** T20 / Q1-A：deliveryNo 锚点 → 交付记录 + 留痕白名单字段。 */
    @Test
    void deliveryNoAnchorReturnsDeliveryAndLogs() throws Exception {
        insertDelivery();
        mockMvc.perform(evidenceInternal(AUDIT_TRAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.delivery.deliveryNo").value("DL000123"))
                .andExpect(jsonPath("$.data.delivery.contractNo").value("CO000123"))
                .andExpect(jsonPath("$.data.delivery.deliveryType").value("FILE"))
                .andExpect(jsonPath("$.data.delivery.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.delivery.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.actionLogs").isArray())
                .andExpect(jsonPath("$.data.actionLogs[0].action").value("DELIVERY_CREATED"))
                .andExpect(jsonPath("$.data.actionLogs[0].operatorNo").value("S-provider"));
    }

    /** T20：锚点不存在 → 404；业务角色 → 403（delivery.internal.read 仅 evidence-internal 持有）。 */
    @Test
    void unknownAnchorAndUnauthorizedRejected() throws Exception {
        mockMvc.perform(evidenceInternal("/api/v1/deliveries/internal/DL999999/audit-trail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));

        insertDelivery();
        mockMvc.perform(get(AUDIT_TRAIL)
                        .header("X-Ctds-Subject", "S-requester").header("X-Ctds-Roles", "requester"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(AUDIT_TRAIL)
                        .header("X-Ctds-Subject", "S-provider").header("X-Ctds-Roles", "provider"))
                .andExpect(status().isForbidden());
    }
}
