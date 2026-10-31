package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * 交付连接器源腿（WBS-3.6.8 W3 / W4，hifi §1-B；规格行为 14 验收 1~3）：
 * <ul>
 *   <li><b>T2</b>：全链成功（判定 → 占用 → 属主核对 → 内部读取 → 释放 → SM4 落包 → 记录与留痕）+
 *       判定拒绝零触达源（占用 / 读取 / 释放桩计数全零）+ 幂等重放不重复取数；</li>
 *   <li><b>T3</b>：失败矩阵（E1~E3 / E5 / E6 / E8 / E15）——finally 释放、零交付记录、零计量、
 *       失败可读映射不透传内部码。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1048576",
            "ctds.delivery.connector.timeout=200ms"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryConnectorIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final ConnectorInternalStub CONNECTOR_STUB = new ConnectorInternalStub();
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String CONNECTOR_NO = "CN000018";
    private static final String SOURCE_NO = "SR000018";

    static {
        try {
            STUB = new StubDependencyServer();
            STORAGE_ROOT = Files.createTempDirectory("w368-connector-storage-");
            KEY_FILE = Files.createTempFile("w368-connector-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, "delivery-file=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_connector_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.connector.base-url", CONNECTOR_STUB::baseUrl);
        registry.add("ctds.delivery.storage-root", STORAGE_ROOT::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
    }

    @BeforeAll
    static void startConnectorStub() throws IOException {
        CONNECTOR_STUB.start();
    }

    @AfterAll
    static void stop() {
        STUB.stop();
        CONNECTOR_STUB.stop();
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
        CONNECTOR_STUB.reset();
    }

    @Test
    void connectorDeliveryFullChainSucceedsWithTrail() throws Exception {
        final MvcResult result = createConnectorDelivery("full");
        assertThat(result.getResponse().getStatus()).as("全链成功").isEqualTo(200);
        final JsonNode data = dataOf(result);
        final String deliveryNo = data.path("deliveryNo").asText();
        assertThat(data.path("deliveryType").asText()).isEqualTo("CONNECTOR");
        assertThat(jdbc.queryForObject(
                "SELECT file_id FROM delivery WHERE delivery_no = ?", String.class, deliveryNo))
                .as("sourceFileId 位记 connectorNo:sourceNo").isEqualTo(CONNECTOR_NO + ":" + SOURCE_NO);
        final String packageId = jdbc.queryForObject(
                "SELECT package_id FROM delivery WHERE delivery_no = ?", String.class, deliveryNo);
        assertThat(packageId).isNotBlank();
        assertThat(Files.exists(STORAGE_ROOT.resolve(packageId))).as("SM4 加密包已落盘").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT checksum FROM delivery WHERE delivery_no = ?", String.class, deliveryNo))
                .as("SM3 明文摘要同口径").isNotBlank();
        assertThat(CONNECTOR_STUB.occupyCount.get()).as("占用登记恰一次").isEqualTo(1);
        assertThat(CONNECTOR_STUB.readCount.get()).as("内部读取恰一次").isEqualTo(1);
        assertThat(CONNECTOR_STUB.releaseCount.get()).as("落库成功后释放恰一次").isEqualTo(1);
        assertThat(CONNECTOR_STUB.lastReadBody.get()).as("读取请求 jobRef = 交付编号（占用同键）")
                .contains(deliveryNo).contains("CO000001");
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE result = 'DENIED'", Integer.class);
        assertThat(deniedRows).as("成功链零拒绝留痕").isZero();
        // 留痕五要素（操作者 / 连接器 / 源 / 合约 / 结果）：交付行字段 + CREATE 成功留痕行承载
        assertThat(jdbc.queryForObject(
                "SELECT requester_no FROM delivery WHERE delivery_no = ?", String.class, deliveryNo))
                .as("操作者 = 发起人").isEqualTo(REQUESTER);
        assertThat(jdbc.queryForObject(
                "SELECT contract_no FROM delivery WHERE delivery_no = ?", String.class, deliveryNo))
                .as("合约编号同源").isEqualTo("CO000001");
        final Integer createRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.create' "
                        + "AND result = 'SUCCESS'", Integer.class);
        assertThat(createRows).as("CREATE 成功留痕恰一条").isEqualTo(1);
    }

    @Test
    void judgmentDeniedNeverTouchesConnector() throws Exception {
        STUB.respondCheckError("1008C0020", "超出合约次数上限", 403);
        final MvcResult result = createConnectorDelivery("denied");
        assertThat(result.getResponse().getStatus()).as("判定拒绝透传合约域原码").isEqualTo(403);
        assertThat(CONNECTOR_STUB.occupyCount.get()).as("拒绝零占用").isZero();
        assertThat(CONNECTOR_STUB.readCount.get()).as("拒绝零触达源").isZero();
        assertThat(CONNECTOR_STUB.releaseCount.get()).as("拒绝零释放调用").isZero();
        final Integer deniedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE action = 'delivery.denied' "
                        + "AND result = 'DENIED'", Integer.class);
        assertThat(deniedRows).as("拒绝留痕一条").isEqualTo(1);
        assertThat(countDeliveries()).as("拒绝零交付记录").isZero();
    }

    @Test
    void idempotentReplayDoesNotReReadSource() throws Exception {
        final String first = dataOf(createConnectorDelivery("idem")).path("deliveryNo").asText();
        CONNECTOR_STUB.readCount.set(0);
        CONNECTOR_STUB.occupyCount.set(0);
        final MvcResult replay = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-conn-idem")
                .contentType(MediaType.APPLICATION_JSON)
                .content(connectorBody())).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(replay).path("deliveryNo").asText()).as("幂等重放返回首次交付").isEqualTo(first);
        assertThat(CONNECTOR_STUB.readCount.get()).as("重放不重复取数").isZero();
        assertThat(CONNECTOR_STUB.occupyCount.get()).as("重放不重复占用").isZero();
    }

    @Test
    void connectorFieldsMissingOrMixedRejected() throws Exception {
        // E1：CONNECTOR 缺 connectorNo / sourceNo
        assertParamInvalid("{\"contractNo\":\"CO000001\",\"deliveryType\":\"CONNECTOR\","
                + "\"purpose\":\"统计分析\",\"territory\":\"本市域\"}", "E1 缺连接器编号");
        // E2：API 混带连接器字段
        assertParamInvalid("{\"contractNo\":\"CO000001\",\"deliveryType\":\"API\","
                + "\"connectorNo\":\"" + CONNECTOR_NO + "\",\"sourceNo\":\"" + SOURCE_NO + "\"}",
                "E2 API 混带连接器字段");
        // E2：FILE 混带连接器字段
        assertParamInvalid("{\"contractNo\":\"CO000001\",\"deliveryType\":\"FILE\","
                + "\"fileId\":\"sample-dataset\",\"connectorNo\":\"" + CONNECTOR_NO + "\"}",
                "E2 FILE 混带连接器字段");
        // E3：编号格式非法
        assertParamInvalid("{\"contractNo\":\"CO000001\",\"deliveryType\":\"CONNECTOR\","
                + "\"connectorNo\":\"CN123\",\"sourceNo\":\"SR18\",\"purpose\":\"统计分析\","
                + "\"territory\":\"本市域\"}", "E3 编号格式非法");
        assertThat(countDeliveries()).as("参数无效零侧效").isZero();
        assertThat(CONNECTOR_STUB.occupyCount.get()).as("参数无效零占用").isZero();
    }

    @Test
    void ownerMismatchRejectedButOccupancyReleased() throws Exception {
        // E5：连接器属主 ≠ 合约提供方（借他方连接器读己方合约标的）
        CONNECTOR_STUB.ownerSubject.set("S-other");
        final MvcResult result = createConnectorDelivery("owner");
        assertThat(result.getResponse().getStatus()).as("属主不一致 403").isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(CONNECTOR_STUB.occupyCount.get()).isEqualTo(1);
        assertThat(CONNECTOR_STUB.readCount.get()).as("属主不一致零读取").isZero();
        assertThat(CONNECTOR_STUB.releaseCount.get()).as("finally 释放兜底").isEqualTo(1);
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void occupyRejectedByConnectorStateReadable() throws Exception {
        // E6：连接器非 REGISTERED（占用谓词 0 行拒绝）→ 业务可读拒绝，不透传内部码
        CONNECTOR_STUB.occupyStatus.set(400);
        CONNECTOR_STUB.occupyBody.set("{\"code\":\"1010C0004\",\"message\":\"当前状态不允许该操作\"}");
        final MvcResult result = createConnectorDelivery("occupy");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).as("对外 1009 段（不透传 1010 内部码）").isEqualTo("1009C0006");
        assertThat(CONNECTOR_STUB.readCount.get()).as("占用被拒零读取").isZero();
        assertThat(CONNECTOR_STUB.releaseCount.get()).as("占用未成功不调用释放").isZero();
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void sourceRejectionMapsToFetchFailed() throws Exception {
        // E8a：适配器返回业务失败（源拒绝）→ 1009 段取数失败 + finally 释放
        CONNECTOR_STUB.readSuccess.set(false);
        CONNECTOR_STUB.readSummary.set("source rejected");
        final MvcResult result = createConnectorDelivery("srcfail");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).as("取数失败新码").isEqualTo("1009S0006");
        assertThat(CONNECTOR_STUB.releaseCount.get()).as("finally 释放兜底").isEqualTo(1);
        assertThat(countDeliveries()).as("零交付记录").isZero();
        assertThat(storageEmpty()).as("零包体残留").isTrue();
        final Integer failureRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_action_log WHERE result = 'FAILURE' "
                        + "AND reason_code = '1009S0006'", Integer.class);
        assertThat(failureRows).as("平台侧失败留痕").isEqualTo(1);
    }

    @Test
    void connectorTransportErrorMapsToFetchFailed() throws Exception {
        // E15：内部面客户端 5xx / 超时 → 取数失败收敛，不重试
        CONNECTOR_STUB.readStatus.set(500);
        final MvcResult result = createConnectorDelivery("http500");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0006");
        assertThat(CONNECTOR_STUB.releaseCount.get()).isEqualTo(1);
        assertThat(CONNECTOR_STUB.readCount.get()).as("失败不重试（N3）").isEqualTo(1);
        assertThat(countDeliveries()).isZero();
    }

    @Test
    void connectorTimeoutMapsToFetchFailed() throws Exception {
        // E15：超时（stub 800ms >> 客户端 200ms）
        CONNECTOR_STUB.readDelayMillis.set(800);
        final MvcResult result = createConnectorDelivery("timeout");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0006");
        assertThat(CONNECTOR_STUB.releaseCount.get()).isEqualTo(1);
        assertThat(countDeliveries()).isZero();
    }

    private void assertParamInvalid(final String body, final String label) throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-conn-bad-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(400);
        assertThat(codeOf(result)).as(label).isEqualTo("1009C0005");
    }

    private static String connectorBody() {
        return "{\"contractNo\":\"CO000001\",\"deliveryType\":\"CONNECTOR\","
                + "\"connectorNo\":\"" + CONNECTOR_NO + "\",\"sourceNo\":\"" + SOURCE_NO + "\","
                + "\"purpose\":\"统计分析\",\"territory\":\"本市域\"}";
    }

    private MvcResult createConnectorDelivery(final String idemTag) throws Exception {
        return mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-conn-" + idemTag)
                .contentType(MediaType.APPLICATION_JSON)
                .content(connectorBody())).andReturn();
    }

    private int countDeliveries() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery", Integer.class);
    }

    private boolean storageEmpty() throws IOException {
        try (var stream = Files.list(STORAGE_ROOT)) {
            return stream.findAny().isEmpty();
        }
    }

    private JsonNode dataOf(final MvcResult result) throws IOException {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private String codeOf(final MvcResult result) throws IOException {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText();
    }

    /** 连接器内部面桩（四端点可编程行为 + 计数；响应契约 = ConnectorInternalClient 解析形态）。 */
    static final class ConnectorInternalStub {

        final AtomicInteger occupyCount = new AtomicInteger();
        final AtomicInteger releaseCount = new AtomicInteger();
        final AtomicInteger readCount = new AtomicInteger();
        final AtomicInteger ownerCount = new AtomicInteger();
        final AtomicReference<String> lastReadBody = new AtomicReference<>();
        final AtomicReference<String> ownerSubject = new AtomicReference<>("S-prov");
        final AtomicInteger occupyStatus = new AtomicInteger(200);
        final AtomicReference<String> occupyBody = new AtomicReference<>(
                "{\"code\":\"0\",\"data\":{\"status\":\"OCCUPY\"}}");
        final AtomicInteger readStatus = new AtomicInteger(200);
        final AtomicBoolean readSuccess = new AtomicBoolean(true);
        final AtomicReference<String> readSummary = new AtomicReference<>("rows=10");
        final AtomicLong readDelayMillis = new AtomicLong();
        private HttpServer server;
        private String base;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 多线程执行器：默认单线程执行器下慢读取（超时用例 800ms）会饿死并发释放请求
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
            server.createContext("/api/v1/connectors/internal", exchange -> handle(exchange));
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String baseUrl() {
            return base;
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        void reset() {
            occupyCount.set(0);
            releaseCount.set(0);
            readCount.set(0);
            ownerCount.set(0);
            lastReadBody.set(null);
            ownerSubject.set("S-prov");
            occupyStatus.set(200);
            occupyBody.set("{\"code\":\"0\",\"data\":{\"status\":\"OCCUPY\"}}");
            readStatus.set(200);
            readSuccess.set(true);
            readSummary.set("rows=10");
            readDelayMillis.set(0);
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            final String method = exchange.getRequestMethod();
            if (path.endsWith("/occupancies") && "POST".equals(method)) {
                occupyCount.incrementAndGet();
                respond(exchange, occupyStatus.get(), occupyBody.get());
                return;
            }
            if (path.contains("/occupancies/") && "DELETE".equals(method)) {
                releaseCount.incrementAndGet();
                respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"status\":\"RELEASED\"}}");
                return;
            }
            if (path.endsWith("/reads") && "POST".equals(method)) {
                readCount.incrementAndGet();
                lastReadBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                final long delay = readDelayMillis.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (readStatus.get() == 200) {
                    respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"success\":"
                            + readSuccess.get() + ",\"summary\":\"" + readSummary.get()
                            + "\",\"sourceNo\":\"" + SOURCE_NO + "\",\"readAt\":\"2026-10-31T00:00:00\"}}");
                } else {
                    respond(exchange, readStatus.get(), "{\"code\":\"1010S0001\",\"message\":\"stub\"}");
                }
                return;
            }
            // GET 连接器视图（属主核对）
            ownerCount.incrementAndGet();
            respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"connectorNo\":\"" + CONNECTOR_NO
                    + "\",\"subjectNo\":\"" + ownerSubject.get() + "\",\"status\":\"REGISTERED\"}}");
        }

        private static void respond(final HttpExchange exchange, final int status, final String body)
                throws IOException {
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
