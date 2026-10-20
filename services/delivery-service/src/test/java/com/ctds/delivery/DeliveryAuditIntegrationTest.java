package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.support.RecordingAuditRecorder;
import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 审计留痕集成测试（hifi §7.3 / §9 T8）：create SUCCESS、拒绝 DENIED、平台失败 FAILURE
 * 均落 DB 动作留痕 + AuditRecorder；字段最小必要且不含本体/路径/密钥。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1024"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(DeliveryAuditIntegrationTest.RecorderConfig.class)
class DeliveryAuditIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final Path SOURCE_ROOT;
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String OTHER = "S-other";
    private static final String SECRET_MARKER = "SENSITIVE-AUDIT-353";
    private static final String KEY_REF = "delivery-file";

    static {
        try {
            STUB = new StubDependencyServer();
            SOURCE_ROOT = Files.createTempDirectory("w353-audit-source-");
            STORAGE_ROOT = Files.createTempDirectory("w353-audit-storage-");
            KEY_FILE = Files.createTempFile("w353-audit-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, KEY_REF + "=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(SOURCE_ROOT.resolve("sample.csv"), SECRET_MARKER
                    + System.lineSeparator() + "id,name\n1,alpha\n", StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_audit_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE_ROOT::toString);
        registry.add("ctds.delivery.storage-root", STORAGE_ROOT::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
        registry.add("ctds.delivery.files[0].file-id", () -> "sample-dataset");
        registry.add("ctds.delivery.files[0].product-id", () -> 13L);
        registry.add("ctds.delivery.files[0].path", () -> "sample.csv");
        registry.add("ctds.delivery.files[0].display-name", () -> "演示数据集.csv");
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingAuditRecorder recorder;
    @Autowired
    private DeliveryProperties properties;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM delivery");
        jdbc.update("DELETE FROM delivery_action_log");
        jdbc.update("UPDATE delivery_no_seq SET next_no = 1 WHERE id = 1");
        try (var stream = Files.list(STORAGE_ROOT)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final Exception ignored) {
                    // 测试清理 best-effort
                }
            });
        }
        STUB.resetCounters();
        STUB.respondSummary("CO000001", 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(13, "DATASET");
        STUB.respondCheckAllowed();
        recorder.clear();
        properties.getFile().setKeyRef(KEY_REF);
    }

    @Test
    void createSuccessAuditsMinimalDetail() throws Exception {
        final MvcResult result = postFile("idem-audit-success");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        final List<AuditEvent> events = recorder.eventsOf("delivery.create");
        assertThat(events).hasSize(1);
        final AuditEvent event = events.get(0);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.targetId()).isEqualTo(data.path("deliveryNo").asText());
        assertThat(event.detail()).containsEntry("deliveryType", "FILE")
                .containsKey("contractNo");
        assertNoSensitiveDetail(event);
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.create' "
                        + "AND result = 'SUCCESS'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void readAndGovernanceDenialsAreAudited() throws Exception {
        final String deliveryNo = createFileDelivery();
        recorder.clear();
        final MvcResult other = mockMvc.perform(get("/api/v1/deliveries/" + deliveryNo)
                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester")).andReturn();
        final MvcResult missing = mockMvc.perform(get("/api/v1/deliveries/DL999999")
                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester")).andReturn();
        final MvcResult governance = mockMvc.perform(
                get("/api/v1/deliveries/governance/DL999999")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", "admin"))
                .andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(governance.getResponse().getStatus()).isEqualTo(404);
        final List<AuditEvent> denied = recorder.eventsOf("delivery.denied");
        assertThat(denied).hasSize(3);
        assertThat(denied).allSatisfy(event -> {
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(event.detail()).containsEntry("reasonCode", "1009C0007");
            assertNoSensitiveDetail(event);
        });
        assertThat(denied).extracting(AuditEvent::targetId).contains(deliveryNo, "DL999999");
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.denied' "
                        + "AND result = 'DENIED' AND reason_code = '1009C0007'", Integer.class);
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void downloadDenialIsAudited() throws Exception {
        final String deliveryNo = createFileDelivery();
        recorder.clear();
        final MvcResult result = mockMvc.perform(
                get("/api/v1/deliveries/" + deliveryNo + "/package")
                        .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        final List<AuditEvent> events = recorder.eventsOf("delivery.package.download");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(events.get(0).detail()).containsEntry("reasonCode", "1009C0009")
                .containsEntry("deliveryType", "FILE");
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log "
                        + "WHERE action = 'delivery.package.download' AND result = 'DENIED' "
                        + "AND reason_code = '1009C0009'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void keyUnavailableAuditsCreateFailure() throws Exception {
        properties.getFile().setKeyRef("missing-key");
        final MvcResult result = postFile("idem-audit-key");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0003");
        final List<AuditEvent> events = recorder.eventsOf("delivery.create");
        assertThat(events).hasSize(1);
        final AuditEvent event = events.get(0);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.detail()).containsEntry("reasonCode", "1009S0003")
                .containsEntry("deliveryType", "FILE");
        assertNoSensitiveDetail(event);
        assertThat(countDeliveries()).isZero();
        try (var files = Files.list(STORAGE_ROOT)) {
            assertThat(files.count()).isZero();
        }
        assertThat(STUB.checkCount()).isEqualTo(1);
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.create' "
                        + "AND result = 'FAILURE' AND reason_code = '1009S0003'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    private String createFileDelivery() throws Exception {
        final MvcResult result = postFile("idem-audit-file-" + java.util.UUID.randomUUID());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return dataOf(result).path("deliveryNo").asText();
    }

    private MvcResult postFile(final String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"FILE\","
                        + "\"fileId\":\"sample-dataset\",\"purpose\":\"风控建模\","
                        + "\"territory\":\"本市域\"}")).andReturn();
    }

    private static void assertNoSensitiveDetail(final AuditEvent event) {
        assertThat(event.detail().values()).noneMatch(value -> value.contains(SECRET_MARKER)
                || value.contains(SOURCE_ROOT.toString())
                || value.contains(STORAGE_ROOT.toString()));
    }

    private JsonNode dataOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private String codeOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText();
    }

    private int countDeliveries() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery", Integer.class);
    }

    @TestConfiguration
    static class RecorderConfig {

        @Bean
        RecordingAuditRecorder recordingAuditRecorder() {
            return new RecordingAuditRecorder();
        }
    }
}
