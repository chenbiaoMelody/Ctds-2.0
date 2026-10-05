package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 目录服务间内部只读端点集成测试（WBS-3.4.3 随卡测试，Q6-A；hifi §9 既有服务改动①）：
 * 权限矩阵（contract-internal 过 / provider·admin 业务角色 403 / 未认证 401——专用权限点
 * catalog.internal.read 仅 contract-internal 持有）；最小暴露 6 字段锚定 + **原始状态值**
 * 透传（DELISTED 等——contract 发起门槛判定源）；不存在 → 1007C0011 同形（零新增码）。
 * 真实 MySQL 8 容器实跑；造数沿 jdbc 直插先例；无 Docker 整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class InternalProductEndpointIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/catalog/internal/data-products";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_internal_it");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @BeforeEach
    void admitAll() {
        given(admissionPort.check(org.mockito.ArgumentMatchers.any()))
                .willReturn(SubjectAdmission.ADMITTED);
    }

    @Test
    void contractInternalReadsRawStatusWithSixAnchoredFields() throws Exception {
        final long listedId = insertProduct("内部端点在架产品", "LISTED", "1.50");
        final long delistedId = insertProduct("内部端点下架产品", "DELISTED", null);
        // 在架产品：6 字段全量
        final JsonNode listed = payload(mockMvc.perform(getFor(listedId, "contract-service",
                "contract-internal")).andReturn());
        assertThat(listed.fieldNames()).toIterable().containsExactlyInAnyOrder("productId",
                "productName", "status", "providerSubjectNo", "pricingModel", "priceAmount");
        assertThat(listed.path("productId").asLong()).isEqualTo(listedId);
        assertThat(listed.path("productName").asText()).isEqualTo("内部端点在架产品");
        assertThat(listed.path("status").asText()).isEqualTo("LISTED");
        assertThat(listed.path("providerSubjectNo").asText()).isEqualTo("S-prov-internal");
        assertThat(listed.path("pricingModel").asText()).isEqualTo("PER_CALL");
        assertThat(listed.path("priceAmount").decimalValue()).isEqualByComparingTo("1.5");
        // 原始状态值透传（DELISTED 非 LISTED——contract 发起门槛按状态判定，端点不过滤）
        final JsonNode delisted = payload(mockMvc.perform(getFor(delistedId, "contract-service",
                "contract-internal")).andReturn());
        assertThat(delisted.path("status").asText()).isEqualTo("DELISTED");
        assertThat(delisted.path("priceAmount").isNull()).isTrue();
    }

    @Test
    void notFoundAnswersC0011SameShape() throws Exception {
        final MvcResult result = mockMvc.perform(getFor(999999L, "contract-service",
                "contract-internal")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        final JsonNode body = rootOf(result);
        assertThat(body.path("code").asText()).isEqualTo("1007C0011");
        assertThat(body.path("message").asText()).isEqualTo("产品不存在或未在架");
    }

    @Test
    void businessRolesAreRejectedWithoutInternalPermission() throws Exception {
        final long productId = insertProduct("内部端点越权对照", "LISTED", "1.00");
        // provider / admin 业务角色均不持 catalog.internal.read → 注解层 403（防状态枚举）
        assertThat(mockMvc.perform(getFor(productId, "S-provider", "provider"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(getFor(productId, "S-admin", "admin"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        // 未认证 → 401
        assertThat(mockMvc.perform(get(BASE + "/" + productId))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ==== 支撑 ====

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getFor(
            final long productId, final String subject, final String roles) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(BASE + "/" + productId)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)
                .accept(MediaType.APPLICATION_JSON);
    }

    private static JsonNode rootOf(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode payload(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(
                    java.nio.charset.StandardCharsets.UTF_8)).path("data");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long insertProduct(final String productName, final String status,
            final String priceAmount) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, "
                        + "status, price_amount, provider_subject_no, dataset_id, category_code, "
                        + "listed_at) VALUES (?, '内部端点测试简介', 'DATASET', 'PER_CALL', ?, ?, "
                        + "'S-prov-internal', 1, 'transport', ?)",
                productName, status, priceAmount, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?",
                Long.class, productName);
    }
}
