package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.infrastructure.JdbcDatasetRepository;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(CatalogDenialAuditFaultIntegrationTest.FaultRepositoryConfig.class)
class CatalogDenialAuditFaultIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_denial_fault_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @MockitoBean
    private SpaceMembershipPort spaceMembershipPort;

    @BeforeEach
    void insertDataset() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
        jdbc.update("DELETE FROM dataset_action_log");
        jdbc.update("DELETE FROM dataset_name_lock");
        jdbc.update("DELETE FROM dataset");
        final LocalDateTime now = LocalDateTime.of(2026, 11, 24, 20, 0);
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, "
                        + "type, intro, semantic_tags, declare_category, declare_level, "
                        + "declare_important, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'DATASET', '简介', '[\"金融\"]', '金融', 'L2', "
                        + "0, 'ACTIVE', ?, ?)",
                "DS20261124000001", 1L, "owner-fault", "故障注入资源", "故障注入资源",
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    @Test
    void missingAndNonOwnerShareAuditFailureShape() throws Exception {
        final long datasetId = jdbc.queryForObject(
                "SELECT id FROM dataset WHERE data_no = 'DS20261124000001'", Long.class);
        final MvcResult nonOwner = mockMvc.perform(authGet("/api/v1/datasets/" + datasetId,
                "intruder-fault")).andReturn();
        final MvcResult missing = mockMvc.perform(authGet("/api/v1/datasets/999997",
                "intruder-fault")).andReturn();

        assertThat(nonOwner.getResponse().getStatus()).as("非属主审计故障 → 500").isEqualTo(500);
        assertThat(missing.getResponse().getStatus()).as("不存在审计故障 → 500").isEqualTo(500);
        final JsonNode nonOwnerBody = MAPPER.readTree(nonOwner.getResponse().getContentAsString());
        final JsonNode missingBody = MAPPER.readTree(missing.getResponse().getContentAsString());
        assertThat(nonOwnerBody.path("code").asText()).isEqualTo("1000S9999");
        assertThat(missingBody.path("code").asText()).isEqualTo("1000S9999");
        assertThat(nonOwnerBody.path("message").asText()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(missingBody.path("message").asText())
                .isEqualTo(nonOwnerBody.path("message").asText());
        assertThat(nonOwnerBody.path("data").isNull()).isTrue();
        assertThat(missingBody.path("data").isNull()).isTrue();
        assertThat(nonOwnerBody.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "traceId", "data");
        assertThat(missingBody.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "traceId", "data");
    }

    @Test
    void allFivePathsShareAuditFailureShape() throws Exception {
        final long datasetId = jdbc.queryForObject(
                "SELECT id FROM dataset WHERE data_no = 'DS20261124000001'", Long.class);
        final List<String> endpoints = List.of("E1", "E2", "E3", "E4", "E5");
        int index = 0;
        for (final String endpoint : endpoints) {
            index++;
            final String subject = "fault-" + endpoint.toLowerCase();
            final MvcResult nonOwner = mockMvc.perform(authRequest(endpoint, datasetId, subject))
                    .andReturn();
            final MvcResult missing = mockMvc.perform(authRequest(endpoint, 999990L + index,
                    subject)).andReturn();
            assertAuditFailure(nonOwner);
            assertAuditFailure(missing);
        }
    }

    @Test
    void sustainedAuditFailureNeverTurnsIntoRateLimit() throws Exception {
        for (int attempt = 1; attempt <= 25; attempt++) {
            final MvcResult result = mockMvc.perform(authGet("/api/v1/datasets/999996",
                    "persistent-fault")).andReturn();
            assertThat(result.getResponse().getStatus()).as("持续审计故障第 " + attempt + " 次").isEqualTo(500);
            assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("1000S9999");
        }
    }

    private static void assertAuditFailure(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("1000S9999");
        assertThat(body.path("message").asText()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(body.path("data").isNull()).isTrue();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authRequest(
            final String endpoint, final long datasetId, final String subject) {
        final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                switch (endpoint) {
                    case "E1" -> get("/api/v1/datasets/" + datasetId);
                    case "E2" -> get("/api/v1/datasets/" + datasetId + "/action-logs");
                    case "E3" -> put("/api/v1/datasets/" + datasetId)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"intro\":\"越权变更\"}");
                    case "E4" -> post("/api/v1/datasets/" + datasetId + "/cancellation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmCancellation\":true}");
                    case "E5" -> post("/api/v1/data-products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"datasetId\":" + datasetId + ",\"productName\":\"越权产品\","
                                    + "\"intro\":\"简介\",\"productType\":\"API\","
                                    + "\"pricingModel\":\"FREE\"}");
                    default -> throw new IllegalArgumentException("未知端点：" + endpoint);
                };
        return request.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "provider");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authGet(
            final String path, final String subject) {
        return get(path).header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "provider");
    }

    @TestConfiguration
    static class FaultRepositoryConfig {

        @Bean
        @Primary
        DatasetRepository faultDatasetRepository(final JdbcClient jdbc) {
            final DatasetRepository delegate = new JdbcDatasetRepository(jdbc);
            return (DatasetRepository) Proxy.newProxyInstance(
                    DatasetRepository.class.getClassLoader(), new Class<?>[] {DatasetRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("insert")) {
                            throw new DataAccessResourceFailureException("injected audit failure");
                        }
                        return method.invoke(delegate, args);
                    });
        }
    }
}
