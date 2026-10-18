package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.crypto.Sm3Service;
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
 * 交付准入与文件交付集成测试（hifi §9 T1/T2/T3/T5/T6 骨架）：
 * 真实 MySQL 容器 + 本地合约/目录 stub；测试先行阶段核心服务未实现 → 红相。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1024"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryAdmissionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final Path SOURCE_ROOT;
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String SECRET_MARKER = "SENSITIVE-CONTENT-353";

    static {
        try {
            STUB = new StubDependencyServer();
            SOURCE_ROOT = Files.createTempDirectory("w353-source-");
            STORAGE_ROOT = Files.createTempDirectory("w353-storage-");
            KEY_FILE = Files.createTempFile("w353-delivery-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, "delivery-file=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            resetSources();
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE_ROOT::toString);
        registry.add("ctds.delivery.storage-root", STORAGE_ROOT::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
        registry.add("ctds.delivery.files[0].file-id", () -> "sample-dataset");
        registry.add("ctds.delivery.files[0].product-id", () -> 13L);
        registry.add("ctds.delivery.files[0].path", () -> "sample.csv");
        registry.add("ctds.delivery.files[0].display-name", () -> "演示数据集.csv");
        registry.add("ctds.delivery.files[1].file-id", () -> "oversize-dataset");
        registry.add("ctds.delivery.files[1].product-id", () -> 13L);
        registry.add("ctds.delivery.files[1].path", () -> "oversize.csv");
        registry.add("ctds.delivery.files[1].display-name", () -> "超限数据集.csv");
        registry.add("ctds.delivery.files[2].file-id", () -> "missing-dataset");
        registry.add("ctds.delivery.files[2].product-id", () -> 13L);
        registry.add("ctds.delivery.files[2].path", () -> "missing.csv");
        registry.add("ctds.delivery.files[2].display-name", () -> "缺失数据集.csv");
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
    private Sm3Service sm3Service;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM delivery");
        jdbc.update("DELETE FROM delivery_action_log");
        jdbc.update("UPDATE delivery_no_seq SET next_no = 1 WHERE id = 1");
        Files.list(STORAGE_ROOT).forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (final Exception ignored) {
                // 测试清理 best-effort
            }
        });
        resetSources();
        STUB.resetCounters();
        STUB.respondSummary("CO000001", 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(13, "DATASET");
        STUB.respondCheckAllowed();
    }

    @Test
    void apiDeliveryCreatesApiRecordWithoutCounting() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult result = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-api-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        assertThat(data.path("deliveryType").asText()).isEqualTo("API");
        assertThat(data.path("packageId").isNull()).isTrue();
        assertThat(countDeliveries()).isEqualTo(1);
        assertThat(STUB.checkCount()).isZero();
    }

    @Test
    void fileDeliveryCreatesEncryptedPackageAndChecksum() throws Exception {
        final MvcResult result = postDelivery(fileBody("sample-dataset"), "idem-file-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        assertThat(data.path("deliveryType").asText()).isEqualTo("FILE");
        final String checksum = data.path("checksum").asText();
        assertThat(checksum).isEqualTo(sm3Service.digestHex(Files.readAllBytes(
                SOURCE_ROOT.resolve("sample.csv"))));
        final Path packageFile = STORAGE_ROOT.resolve(data.path("packageId").asText());
        assertThat(packageFile).exists();
        assertThat(Files.readAllBytes(packageFile)).isNotEqualTo(
                Files.readAllBytes(SOURCE_ROOT.resolve("sample.csv")));
        assertThat(countDeliveries()).isEqualTo(1);
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(STUB.lastCheckBody()).contains("风控建模", "本市域");
    }

    @Test
    void providerCannotInitiate() throws Exception {
        // 提供方主体即使持有 delivery.request 权限（例如同时是其他合约的需求方），
        // 业务层仍按"发起人 = 合约需求方"拒绝（1009C0003）。
        final MvcResult result = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-provider-1", PROVIDER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void nonEffectiveContractPassThrough() throws Exception {
        STUB.respondSummaryError("1008C0013", "合约当前状态不允许该操作", 409);
        final MvcResult result = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-state-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1008C0013");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void nonParticipantPassThrough() throws Exception {
        STUB.respondSummaryError("1008C0012", "合约不存在或不可见", 404);
        final MvcResult result = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-visible-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1008C0012");
    }

    @Test
    void formMismatchRejected() throws Exception {
        final MvcResult api = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-form-api", REQUESTER, "requester");
        assertThat(api.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(api)).isEqualTo("1009C0006");
        STUB.respondProduct(13, "API");
        final MvcResult file = postDelivery(fileBody("sample-dataset"), "idem-form-file",
                REQUESTER, "requester");
        assertThat(file.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(file)).isEqualTo("1009C0006");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void policyDeniedPassThroughWithZeroCountAndNoPackage() throws Exception {
        STUB.respondCheckError("1008C0020", "越界使用被拒绝，触发要素见留痕", 403);
        final MvcResult result = postDelivery(fileBody("sample-dataset"), "idem-policy-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1008C0020");
        assertThat(countDeliveries()).isZero();
        assertThat(Files.list(STORAGE_ROOT).count()).isZero();
    }

    @Test
    void dependencyUnavailableFailsClosed() throws Exception {
        STUB.respondSummaryError("1008S0003", "", 500);
        final MvcResult result = postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-unavailable-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void fileOutOfScopeRejectedBeforePolicy() throws Exception {
        final MvcResult result = postDelivery(fileBody("not-registered"), "idem-scope-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0006");
        assertThat(STUB.checkCount()).isZero();
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void fileTooLargeRejected() throws Exception {
        Files.write(SOURCE_ROOT.resolve("oversize.csv"),
                new byte[2048]);
        final MvcResult result = postDelivery(fileBody("oversize-dataset"), "idem-large-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0008");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void missingFileFailsClosed() throws Exception {
        Files.deleteIfExists(SOURCE_ROOT.resolve("missing.csv"));
        final MvcResult result = postDelivery(fileBody("missing-dataset"), "idem-missing-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void idempotentReplayReturnsFirstResult() throws Exception {
        final MvcResult first = postDelivery(fileBody("sample-dataset"), "idem-replay-1",
                REQUESTER, "requester");
        final MvcResult second = postDelivery(fileBody("sample-dataset"), "idem-replay-1",
                REQUESTER, "requester");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(second).path("deliveryNo").asText())
                .isEqualTo(dataOf(first).path("deliveryNo").asText());
        assertThat(dataOf(second).path("packageId").asText())
                .isEqualTo(dataOf(first).path("packageId").asText());
        assertThat(countDeliveries()).isEqualTo(1);
        assertThat(STUB.checkCount()).isEqualTo(1);
    }

    @Test
    void missingOrInvalidIdempotencyKeyRejected() throws Exception {
        final MvcResult missing = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(missing)).isEqualTo("1009C0005");
        final MvcResult invalid = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}", "bad key!", REQUESTER,
                "requester");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(invalid)).isEqualTo("1009C0005");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void invalidContractNoRejectedBeforeDependencyCall() throws Exception {
        final MvcResult result = postDelivery("{\"contractNo\":\"bad value!\",\"deliveryType\":\"API\"}",
                "idem-contract-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0005");
        assertThat(STUB.summaryCount()).isZero();
    }

    private MvcResult postDelivery(final String body, final String idempotencyKey,
            final String subject, final String roles) throws Exception {
        return mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", subject)
                .header("X-Ctds-Roles", roles)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private static String fileBody(final String fileId) {
        return "{\"contractNo\":\"CO000001\",\"deliveryType\":\"FILE\",\"fileId\":\"" + fileId
                + "\",\"purpose\":\"风控建模\",\"territory\":\"本市域\"}";
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

    private static void resetSources() throws Exception {
        Files.writeString(SOURCE_ROOT.resolve("sample.csv"), SECRET_MARKER + System.lineSeparator()
                + "id,name\n1,alpha\n2,beta\n", StandardCharsets.UTF_8);
        Files.write(SOURCE_ROOT.resolve("oversize.csv"), new byte[512]);
        Files.write(SOURCE_ROOT.resolve("missing.csv"), new byte[128]);
    }
}
