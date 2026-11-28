package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.FixedClockConfig;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockConfig.class)
class CatalogDenialProtectionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ENDPOINTS = List.of("E1", "E2", "E3", "E4", "E5");

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_denial_protection_it");
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
    void defaultsAndClean() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
        jdbc.update("DELETE FROM product_favorite");
        jdbc.update("DELETE FROM product_subscription");
        jdbc.update("DELETE FROM product_interaction_log");
        jdbc.update("DELETE FROM product_action_log");
        jdbc.update("DELETE FROM data_product");
        jdbc.update("DELETE FROM dataset_action_log");
        jdbc.update("DELETE FROM dataset_name_lock");
        jdbc.update("DELETE FROM dataset");
    }

    @Test
    void allFivePathsAuditMissingAndNonOwnerWithSameShape() throws Exception {
        final long datasetId = insertDataset("owner-all", "五路径资源");
        int index = 0;
        for (final String endpoint : ENDPOINTS) {
            index++;
            final String subject = "deny-" + endpoint.toLowerCase();
            final long missingId = 900000L + index;
            final MvcResult nonOwner = mockMvc.perform(auth(request(endpoint, datasetId), subject))
                    .andReturn();
            final MvcResult missing = mockMvc.perform(auth(request(endpoint, missingId), subject))
                    .andReturn();
            assertDenied(nonOwner);
            assertDenied(missing);
            assertThat(nonOwner.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
            assertThat(body(nonOwner).path("code").asText()).isEqualTo(body(missing).path("code").asText());
            assertThat(body(nonOwner).path("message").asText())
                    .isEqualTo(body(missing).path("message").asText());
            assertAudit(subject, actionOf(endpoint), datasetId);
            assertAudit(subject, actionOf(endpoint), missingId);
        }
    }

    @Test
    void detailAndActionLogsShareOneDenialWindow() throws Exception {
        final long datasetId = insertDataset("owner-shared", "共享读面窗口");
        final String subject = "shared-read";
        for (int attempt = 1; attempt <= 20; attempt++) {
            final String path = attempt % 2 == 0
                    ? "/api/v1/datasets/" + datasetId + "/action-logs"
                    : "/api/v1/datasets/" + datasetId;
            final MvcResult denied = mockMvc.perform(auth(get(path), subject)).andReturn();
            assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        }
        final MvcResult limited = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId), subject))
                .andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).path("code").asText()).isEqualTo("1007C0022");
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND actor_subject_no = ? AND action = 'DENIED_READ'", Long.class, datasetId,
                subject)).isEqualTo(1);
    }

    @Test
    void allFivePathsRateLimitMissingAndNonOwnerAfterTwentyDenials() throws Exception {
        final long knownId = insertDataset("owner-limit", "限流已知资源");
        int index = 0;
        for (final String endpoint : ENDPOINTS) {
            index++;
            final long missingId = 920000L + index;
            final String subject = "limit-" + endpoint.toLowerCase();
            for (final long targetId : List.of(knownId, missingId)) {
                for (int attempt = 1; attempt <= 20; attempt++) {
                    final MvcResult denied = mockMvc.perform(auth(request(endpoint, targetId), subject))
                            .andReturn();
                    assertThat(denied.getResponse().getStatus()).as(endpoint + " 前 20 次").isEqualTo(400);
                }
                final MvcResult limited = mockMvc.perform(auth(request(endpoint, targetId), subject))
                        .andReturn();
                assertThat(limited.getResponse().getStatus()).as(endpoint + " 第 21 次").isEqualTo(429);
                assertThat(body(limited).path("code").asText()).isEqualTo("1007C0022");
                assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                        + "AND actor_subject_no = ? AND action = ?", Long.class, targetId, subject,
                        actionOf(endpoint))).isEqualTo(1);
            }
        }
    }

    @Test
    void subjectLimitStopsAuditWhenObjectIdsRotate() throws Exception {
        final String subject = "rotating-id-intruder";
        for (int attempt = 0; attempt < 100; attempt++) {
            final MvcResult denied = mockMvc.perform(auth(
                    get("/api/v1/datasets/" + (950000L + attempt)), subject)).andReturn();
            assertDenied(denied);
        }
        final MvcResult limited = mockMvc.perform(auth(
                get("/api/v1/datasets/950100"), subject)).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).path("code").asText()).isEqualTo("1007C0022");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log "
                + "WHERE actor_subject_no = ? AND action = 'DENIED_READ'", Long.class, subject))
                .isEqualTo(100);
    }

    @Test
    void authenticatedSubjectBoundariesAreAuditedWithoutTruncation() throws Exception {
        int index = 0;
        for (final int length : List.of(25, 108, 128)) {
            index++;
            final String subject = "s".repeat(length);
            final long missingId = 930000L + index;
            final MvcResult denied = mockMvc.perform(auth(get("/api/v1/datasets/" + missingId),
                    subject)).andReturn();
            assertDenied(denied);
            assertAudit(subject, "DENIED_READ", missingId);
            assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(dedup_key) FROM dataset_action_log "
                    + "WHERE actor_subject_no = ? AND dataset_id = ?", Integer.class, subject,
                    missingId)).isLessThanOrEqualTo(255);
        }
    }

    @Test
    void cancellationValidatesConfirmationAfterOwnershipAndState() throws Exception {
        final long datasetId = insertDataset("owner-cancel-order", "注销顺序资源");
        final String subject = "cancel-order-intruder";
        for (final long targetId : List.of(datasetId, 940001L)) {
            final MvcResult denied = mockMvc.perform(auth(
                    post("/api/v1/datasets/" + targetId + "/cancellation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmCancellation\":false}"), subject)).andReturn();
            assertDenied(denied);
            assertAudit(subject, "DENIED_CANCEL", targetId);
        }
        final MvcResult owner = mockMvc.perform(auth(
                post("/api/v1/datasets/" + datasetId + "/cancellation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmCancellation\":false}"), "owner-cancel-order"))
                .andReturn();
        assertThat(owner.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(owner).path("code").asText()).isEqualTo("1000C0001");

        jdbc.update("UPDATE dataset SET status = 'DELETED' WHERE id = ?", datasetId);
        final MvcResult deleted = mockMvc.perform(auth(
                post("/api/v1/datasets/" + datasetId + "/cancellation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmCancellation\":false}"), "owner-cancel-order"))
                .andReturn();
        assertThat(deleted.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(deleted).path("code").asText()).isEqualTo("1007C0007");
    }

    @Test
    void ownerReadsDoNotConsumeDenialWindow() throws Exception {
        final long datasetId = insertDataset("owner-normal", "属主正常读取");
        for (int attempt = 0; attempt < 25; attempt++) {
            final MvcResult ok = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId),
                    "owner-normal")).andReturn();
            assertThat(ok.getResponse().getStatus()).as(body(ok).toString()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND actor_subject_no = 'owner-normal' AND action = 'DENIED_READ'", Long.class,
                datasetId)).isZero();
    }

    private static MockHttpServletRequestBuilder request(final String endpoint, final long datasetId) {
        return switch (endpoint) {
            case "E1" -> get("/api/v1/datasets/" + datasetId);
            case "E2" -> get("/api/v1/datasets/" + datasetId + "/action-logs");
            case "E3" -> put("/api/v1/datasets/" + datasetId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"intro\":\"越权变更\"}");
            case "E4" -> post("/api/v1/datasets/" + datasetId + "/cancellation")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"confirmCancellation\":true}");
            case "E5" -> post("/api/v1/data-products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(productBody(datasetId));
            default -> throw new IllegalArgumentException("未知端点：" + endpoint);
        };
    }

    private static String actionOf(final String endpoint) {
        return switch (endpoint) {
            case "E1", "E2" -> "DENIED_READ";
            case "E3" -> "DENIED_UPDATE";
            case "E4" -> "DENIED_CANCEL";
            case "E5" -> "DENIED_CREATE";
            default -> throw new IllegalArgumentException("未知端点：" + endpoint);
        };
    }

    private void assertAudit(final String subject, final String action, final long datasetId) {
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT space_id, dataset_id, action, from_value, to_value, result, reason_code, "
                        + "created_at, dedup_key FROM dataset_action_log WHERE actor_subject_no = ? "
                        + "AND dataset_id = ? AND action = ?", subject, datasetId, action);
        assertThat(row.get("space_id")).isNull();
        assertThat(row.get("dataset_id")).isEqualTo(datasetId);
        assertThat(row.get("from_value")).isNull();
        assertThat(row.get("to_value")).isNull();
        assertThat(row.get("result")).isEqualTo("DENIED");
        assertThat(row.get("reason_code")).isEqualTo("C0003");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("dedup_key")).isNotNull();
        assertThat(((String) row.get("dedup_key")).length()).isLessThanOrEqualTo(255);
    }

    private static void assertDenied(final MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                .isEqualTo(400);
        final JsonNode root = body(result);
        assertThat(root.path("code").asText()).isEqualTo("1000C0003");
        assertThat(root.path("message").asText()).isEqualTo("资源不存在");
        assertThat(root.path("data").isNull()).isTrue();
        assertThat(root.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "traceId", "data");
    }

    private long insertDataset(final String owner, final String name) {
        final LocalDateTime now = LocalDateTime.of(2026, 11, 24, 20, 0);
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, "
                        + "type, intro, semantic_tags, declare_category, declare_level, "
                        + "declare_important, status, created_at, updated_at) "
                        + "VALUES (?, 1, ?, ?, ?, 'DATASET', '简介', '[\"金融\"]', '金融', 'L2', "
                        + "0, 'ACTIVE', ?, ?)",
                "DS20261124" + owner, owner, name, name, Timestamp.valueOf(now),
                Timestamp.valueOf(now));
        return jdbc.queryForObject("SELECT id FROM dataset WHERE owner_subject_no = ?", Long.class,
                owner);
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "provider");
    }

    private static JsonNode body(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String productBody(final long datasetId) {
        return "{\"datasetId\":" + datasetId + ",\"productName\":\"越权产品\",\"intro\":\"简介\","
                + "\"productType\":\"API\",\"pricingModel\":\"FREE\"}";
    }
}
