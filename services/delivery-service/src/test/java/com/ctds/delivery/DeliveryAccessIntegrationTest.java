package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 交付读面/下载/治理与防枚举测试（hifi §9 T4/T8 骨架）：
 * 参与者可见、非参与方同码同文、提供方不可领取、治理读面留痕、响应/DB 不含本体。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1024"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryAccessIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final Path SOURCE_ROOT;
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String OTHER = "S-other";
    private static final String SECRET_MARKER = "SENSITIVE-CONTENT-353";

    static {
        try {
            STUB = new StubDependencyServer();
            SOURCE_ROOT = Files.createTempDirectory("w353-access-source-");
            STORAGE_ROOT = Files.createTempDirectory("w353-access-storage-");
            KEY_FILE = Files.createTempFile("w353-access-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, "delivery-file=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(SOURCE_ROOT.resolve("sample.csv"), SECRET_MARKER
                    + System.lineSeparator() + "id,name\n1,alpha\n", StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_access_it");
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
    }

    @Test
    void participantsCanReadAndNonParticipantsGetSameShape() throws Exception {
        final String deliveryNo = createFileDelivery();
        final MvcResult requester = getDelivery(deliveryNo, REQUESTER, "requester");
        final MvcResult provider = getDelivery(deliveryNo, PROVIDER, "provider");
        final MvcResult other = getDelivery(deliveryNo, OTHER, "requester");
        final MvcResult missing = getDelivery("DL999999", OTHER, "requester");
        assertThat(requester.getResponse().getStatus()).isEqualTo(200);
        assertThat(provider.getResponse().getStatus()).isEqualTo(200);
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(other)).isEqualTo("1009C0007");
        assertThat(codeOf(missing)).isEqualTo("1009C0007");
        assertThat(messageOf(other)).isEqualTo(messageOf(missing));
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.denied' "
                        + "AND result = 'DENIED' AND reason_code = '1009C0007'", Integer.class);
        assertThat(deniedRows).isEqualTo(2);
    }

    @Test
    void requesterCanDownloadEncryptedPackageOnly() throws Exception {
        final String deliveryNo = createFileDelivery();
        final String checksum = jdbc.queryForObject(
                "SELECT checksum FROM delivery WHERE delivery_no = ?", String.class, deliveryNo);
        final MvcResult result = mockMvc.perform(get("/api/v1/deliveries/" + deliveryNo + "/package")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("X-Ctds-Sm3")).isEqualTo(checksum);
        final byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(0);
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain(SECRET_MARKER);
    }

    @Test
    void providerCannotDownloadPackage() throws Exception {
        final String deliveryNo = createFileDelivery();
        final MvcResult result = mockMvc.perform(get("/api/v1/deliveries/" + deliveryNo + "/package")
                .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0009");
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log "
                        + "WHERE action = 'delivery.package.download' AND result = 'DENIED' "
                        + "AND reason_code = '1009C0009'", Integer.class);
        assertThat(deniedRows).isEqualTo(1);
    }

    @Test
    void nonParticipantCannotDownloadPackage() throws Exception {
        final String deliveryNo = createFileDelivery();
        final MvcResult result = mockMvc.perform(get("/api/v1/deliveries/" + deliveryNo + "/package")
                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0007");
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log "
                        + "WHERE action = 'delivery.package.download' AND result = 'DENIED' "
                        + "AND reason_code = '1009C0007'", Integer.class);
        assertThat(deniedRows).isEqualTo(1);
    }

    @Test
    void listIsFilteredByCaller() throws Exception {
        createFileDelivery();
        createApiDelivery();
        assertThat(listCount(REQUESTER, "requester")).isEqualTo(2);
        assertThat(listCount(PROVIDER, "provider")).isEqualTo(2);
        assertThat(listCount(OTHER, "requester")).isZero();
    }

    @Test
    void governanceReadIsAllowedAndAudited() throws Exception {
        final String deliveryNo = createFileDelivery();
        final MvcResult detail = mockMvc.perform(get("/api/v1/deliveries/governance/" + deliveryNo)
                .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", "admin")).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        final MvcResult list = mockMvc.perform(get("/api/v1/deliveries/governance")
                .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", "admin")).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        final Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.governance.read'",
                Integer.class);
        assertThat(auditCount).isEqualTo(2);
        final MvcResult denied = mockMvc.perform(get("/api/v1/deliveries/governance/" + deliveryNo)
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void recordAndActionLogDoNotContainBody() throws Exception {
        createFileDelivery();
        final Integer leaked = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM delivery WHERE CONCAT_WS('|', delivery_no, contract_no, "
                        + "product_name, file_id, file_name, package_id, checksum) LIKE ?) + "
                        + "(SELECT COUNT(*) FROM delivery_action_log WHERE CONCAT_WS('|', delivery_no, "
                        + "contract_no, operator_no, action, reason_code) LIKE ?)",
                Integer.class, "%" + SECRET_MARKER + "%", "%" + SECRET_MARKER + "%");
        assertThat(leaked).isZero();
        final MvcResult detail = getDelivery("DL000001", REQUESTER, "requester");
        assertThat(detail.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(SECRET_MARKER);
    }

    @Test
    void governanceMissingIsDeniedAndAudited() throws Exception {
        final MvcResult result = mockMvc.perform(
                get("/api/v1/deliveries/governance/DL999999")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", "admin"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0007");
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.denied' "
                        + "AND result = 'DENIED' AND reason_code = '1009C0007'", Integer.class);
        assertThat(deniedRows).isEqualTo(1);
    }

    @Test
    void missingPackageFailsClosed() throws Exception {
        final String deliveryNo = createFileDelivery();
        final String packageId = jdbc.queryForObject(
                "SELECT package_id FROM delivery WHERE delivery_no = ?", String.class, deliveryNo);
        Files.deleteIfExists(STORAGE_ROOT.resolve(packageId));
        final MvcResult result = mockMvc.perform(
                get("/api/v1/deliveries/" + deliveryNo + "/package")
                        .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0003");
    }

    @Test
    void listPaginationAndOrderBy() throws Exception {
        createApiDelivery();
        STUB.respondProduct(13, "DATASET");
        createFileDelivery();
        final MvcResult page100 = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .param("pageSize", "100")).andReturn();
        assertThat(page100.getResponse().getStatus()).isEqualTo(200);
        final MvcResult page101 = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .param("pageSize", "101")).andReturn();
        assertThat(page101.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(page101)).isEqualTo("1000C0001");
        final MvcResult ordered = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .param("orderBy", "deliveryType,asc")).andReturn();
        assertThat(ordered.getResponse().getStatus()).isEqualTo(200);
        final JsonNode list = MAPPER.readTree(
                ordered.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("list");
        assertThat(list.get(0).path("deliveryType").asText()).isEqualTo("API");
        final MvcResult badOrder = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .param("orderBy", "password")).andReturn();
        assertThat(badOrder.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badOrder)).isEqualTo("1000C0001");
        final MvcResult badType = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .param("pageSize", "abc")).andReturn();
        assertThat(badType.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badType)).isEqualTo("1000C0001");
    }

    @Test
    void providerRoleIsRejectedAtPermissionLayer() throws Exception {
        final MvcResult initiate = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "provider")
                .header("X-Idempotency-Key", "idem-provider-role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}")).andReturn();
        assertThat(initiate.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(initiate)).isEqualTo("1000C0005");
        final String deliveryNo = createFileDelivery();
        final MvcResult download = mockMvc.perform(
                get("/api/v1/deliveries/" + deliveryNo + "/package")
                        .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(download)).isEqualTo("1000C0005");
    }

    private String createFileDelivery() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-access-file-" + java.util.UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"FILE\","
                        + "\"fileId\":\"sample-dataset\",\"purpose\":\"风控建模\","
                        + "\"territory\":\"本市域\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return dataOf(result).path("deliveryNo").asText();
    }

    private String createApiDelivery() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult result = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-access-api-" + java.util.UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return dataOf(result).path("deliveryNo").asText();
    }

    private MvcResult getDelivery(final String deliveryNo, final String subject, final String roles)
            throws Exception {
        return mockMvc.perform(get("/api/v1/deliveries/" + deliveryNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    private int listCount(final String subject, final String roles) throws Exception {
        final MvcResult result = mockMvc.perform(get("/api/v1/deliveries")
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("total").asInt();
    }

    private JsonNode dataOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private String codeOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText();
    }

    private String messageOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("message").asText();
    }
}
