package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
    @Autowired
    private Sm4Service sm4Service;

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
        final byte[] sourceBytes = Files.readAllBytes(SOURCE_ROOT.resolve("sample.csv"));
        final byte[] envelope = Files.readAllBytes(packageFile);
        assertThat(envelope).isNotEqualTo(sourceBytes);
        assertThat(sm4Service.decrypt(envelope, "delivery-file")).isEqualTo(sourceBytes);
        final byte[] tampered = envelope.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThatThrownBy(() -> sm4Service.decrypt(tampered, "delivery-file"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1001C0002"));
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
    void emptyDependencyBodiesFailClosed() throws Exception {
        STUB.respondSummaryRaw("", 200);
        final MvcResult summary = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-empty-summary-1", REQUESTER, "requester");
        assertThat(summary.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(summary)).isEqualTo("1009S0002");
        STUB.respondSummary("CO000001", 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProductRaw("", 200);
        final MvcResult product = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-empty-product-1", REQUESTER, "requester");
        assertThat(product.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(product)).isEqualTo("1009S0002");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void malformedDependencyMetadataFailsClosed() throws Exception {
        STUB.respondProduct(13, "UNKNOWN");
        final MvcResult unknownType = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-unknown-type-1", REQUESTER, "requester");
        assertThat(unknownType.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unknownType)).isEqualTo("1009S0002");

        STUB.respondProduct(13, "API");
        STUB.respondSummaryError("1008C9999", "", 409);
        final MvcResult unknownCode = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-unknown-code-1", REQUESTER, "requester");
        assertThat(unknownCode.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unknownCode)).isEqualTo("1009S0002");

        STUB.respondSummaryRaw("{\"code\":\"0\",\"message\":\"ok\",\"data\":{"
                + "\"contractNo\":\"CO000001\",\"productId\":13,\"productName\":\"演示数据集\","
                + "\"providerNo\":\"S-prov\",\"status\":\"EFFECTIVE\","
                + "\"effectiveAt\":\"2026-10-01T00:00:00\"}}", 200);
        final MvcResult missingField = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-missing-field-1", REQUESTER, "requester");
        assertThat(missingField.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(missingField)).isEqualTo("1009S0002");

        STUB.respondSummaryRaw("{\"code\":\"0\",\"message\":\"ok\",\"data\":{"
                + "\"contractNo\":\"CO000001\",\"productId\":13,\"productName\":\"演示数据集\","
                + "\"applicantNo\":\"S-req\",\"providerNo\":\"S-prov\",\"status\":\"EFFECTIVE\","
                + "\"effectiveAt\":\"not-a-date\"}}", 200);
        final MvcResult badDate = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-bad-date-1", REQUESTER, "requester");
        assertThat(badDate.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(badDate)).isEqualTo("1009S0002");
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void checkUnavailableFailsClosedWithNoRecordOrPackage() throws Exception {
        STUB.respondCheckError("1008S0003", "", 500);
        final MvcResult result = postDelivery(fileBody("sample-dataset"),
                "idem-check-unavailable-1", REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(countDeliveries()).isZero();
        try (var files = Files.list(STORAGE_ROOT)) {
            assertThat(files.count()).isZero();
        }
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

    @Test
    void apiDeliveryReplayReturnsFirstResult() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult first = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-api-replay-1", REQUESTER, "requester");
        final MvcResult second = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-api-replay-1", REQUESTER, "requester");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(second).path("deliveryNo").asText())
                .isEqualTo(dataOf(first).path("deliveryNo").asText());
        assertThat(countDeliveries()).isEqualTo(1);
        assertThat(STUB.checkCount()).isZero();
    }

    @Test
    void idempotencyKeyReuseReturnsFirstResultEvenWithDifferentBody() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult first = postDelivery(
                "{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}",
                "idem-reuse-1", REQUESTER, "requester");
        final MvcResult second = postDelivery(fileBody("sample-dataset"), "idem-reuse-1",
                REQUESTER, "requester");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(second).path("deliveryType").asText()).isEqualTo("API");
        assertThat(countDeliveries()).isEqualTo(1);
        assertThat(STUB.checkCount()).isZero();
    }

    @Test
    void malformedJsonRejectedAsParamInvalid() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-malformed-1")
                .contentType(MediaType.APPLICATION_JSON).content("{not-json")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0005");
        assertThat(countDeliveries()).isZero();
    }

    /** DB-53②：依赖 check 空体独立用例（与 summary/product 空体同为 fail-closed 503 1009S0002）。 */
    @Test
    void checkEmptyBodyFailsClosed() throws Exception {
        STUB.respondCheckRaw("", 200);
        final MvcResult result = postDelivery(fileBody("sample-dataset"), "idem-check-empty-1",
                REQUESTER, "requester");
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(countDeliveries()).isZero();
        try (var files = Files.list(STORAGE_ROOT)) {
            assertThat(files.count()).isZero();
        }
    }

    /** DB-53⑦：请求体为 JSON 字面量 null → 1009C0005（不可读请求体同码同文拒绝面）。 */
    @Test
    void jsonNullBodyRejectedAsParamInvalid() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-null-1")
                .contentType(MediaType.APPLICATION_JSON).content("null")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0005");
        assertThat(countDeliveries()).isZero();
    }

    /**
     * B2 对账锚（交付侧）：deliveries 按形态行数 ≡ 放行/成功路径计数收敛——
     * FILE 行数 ≡ check 放行次数；策略拒绝路径 check 已调用但零新增行；
     * API 发起"记录不计数"语义显式锚定（行数 +1、checkCount 不变）。
     */
    @Test
    void deliveryRowsConvergeWithGrantedChecksPerForm() throws Exception {
        postDelivery(fileBody("sample-dataset"), "idem-b2-1", REQUESTER, "requester");
        postDelivery(fileBody("sample-dataset"), "idem-b2-2", REQUESTER, "requester");
        assertThat(rowsByType("FILE")).as("FILE 放行 2 次 ≡ FILE 行 2").isEqualTo(2);
        assertThat(STUB.checkCount()).isEqualTo(2);

        STUB.respondCheckError("1008C0020", "越界使用被拒绝，触发要素见留痕", 403);
        final MvcResult denied = postDelivery(fileBody("sample-dataset"), "idem-b2-3",
                REQUESTER, "requester");
        assertThat(codeOf(denied)).isEqualTo("1008C0020");
        assertThat(STUB.checkCount()).as("拒绝路径 check 已调用但不放行").isEqualTo(3);
        assertThat(rowsByType("FILE")).as("拒绝路径零新增交付行").isEqualTo(2);

        STUB.respondCheckAllowed();
        STUB.respondProduct(13, "API");
        postDelivery("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\"}", "idem-b2-4",
                REQUESTER, "requester");
        assertThat(rowsByType("API")).as("API 发起记录 1 行").isEqualTo(1);
        assertThat(STUB.checkCount()).as("API 记录不计数（真正调用由网关判定计数）").isEqualTo(3);
    }

    private int rowsByType(final String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE delivery_type = ?",
                Integer.class, type);
    }

    @Test
    void concurrentSameKeyCreatesOneRecordAndOnePackage() throws Exception {
        final String key = "idem-concurrent-1";
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final Callable<MvcResult> call = () -> {
                start.await();
                return postDelivery(fileBody("sample-dataset"), key, REQUESTER, "requester");
            };
            final Future<MvcResult> first = pool.submit(call);
            final Future<MvcResult> second = pool.submit(call);
            start.countDown();
            final MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            final MvcResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isIn(200, 400);
            assertThat(secondResult.getResponse().getStatus()).isIn(200, 400);
            assertThat(Stream.of(firstResult, secondResult)
                    .filter(result -> result.getResponse().getStatus() == 200).count())
                    .isGreaterThanOrEqualTo(1);
            assertThat(countDeliveries()).isEqualTo(1);
            try (var files = Files.list(STORAGE_ROOT)) {
                assertThat(files.count()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
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
