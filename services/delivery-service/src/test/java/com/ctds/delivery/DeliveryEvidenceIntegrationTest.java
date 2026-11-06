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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 交付环节存证埋点集成测试（WBS-3.8.3 hifi §2 E5、§8 T21~T23；规格 B1-1 / B1-3 / B2-2 / Q10）：
 * 新建交付 → 1 条 {@code DELIVERY/DELIVERY_CREATED}（单据号逐字 = delivery_no，DID 取合约摘要新增字段）；
 * 幂等重放零新增；判定拒绝零事件。桩 = JDK HttpServer（合约/目录 + 存证写面）。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1024"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryEvidenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final EvidenceStub EVIDENCE;
    private static final Path SOURCE_ROOT;
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";

    static {
        try {
            STUB = new StubDependencyServer();
            EVIDENCE = new EvidenceStub();
            SOURCE_ROOT = Files.createTempDirectory("w383-evidence-source-");
            STORAGE_ROOT = Files.createTempDirectory("w383-evidence-storage-");
            KEY_FILE = Files.createTempFile("w383-evidence-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, "delivery-file=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(SOURCE_ROOT.resolve("sample.csv"), "id,name\n1,alpha\n",
                    StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_evidence");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE_ROOT::toString);
        registry.add("ctds.delivery.storage-root", STORAGE_ROOT::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
        registry.add("ctds.delivery.files[0].file-id", () -> "sample-dataset");
        registry.add("ctds.delivery.files[0].product-id", () -> 13L);
        registry.add("ctds.delivery.files[0].path", () -> "sample.csv");
        registry.add("ctds.delivery.files[0].display-name", () -> "演示数据集.csv");
        registry.add("ctds.evidence.client.base-url", EVIDENCE::baseUrl);
    }

    @AfterAll
    static void stop() {
        STUB.stop();
        EVIDENCE.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM delivery");
        jdbc.update("DELETE FROM delivery_action_log");
        jdbc.update("UPDATE delivery_no_seq SET next_no = 1 WHERE id = 1");
        STUB.resetCounters();
        STUB.respondSummary("CO000001", 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(13, "DATASET");
        STUB.respondCheckAllowed();
        EVIDENCE.reset();
    }

    // ==== T21 交付创建 → 1 条 DELIVERY/DELIVERY_CREATED ====

    @Test
    void deliveryCreatedEmitsSingleDeliveryEvent() throws Exception {
        final MvcResult result = postDelivery("idem-e1");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final String deliveryNo = MAPPER.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("deliveryNo").asText();
        assertThat(deliveryNo).matches("DL\\d{6}");

        final List<JsonNode> events = EVIDENCE.events();
        assertThat(events).hasSize(1);
        final JsonNode event = events.get(0);
        assertThat(event.path("stageType").asText()).isEqualTo("DELIVERY");
        assertThat(event.path("operationType").asText()).isEqualTo("DELIVERY_CREATED");
        // 单据号逐字透传（B1-3：与落库 delivery_no 完全一致）
        assertThat(event.path("businessNo").asText()).isEqualTo(deliveryNo);
        assertThat(event.path("subjectDid").asText())
                .isEqualTo(StubDependencyServer.REQUESTER_DID);
        assertThat(event.path("actorSubjectNo").asText()).isEqualTo(REQUESTER);
        assertThat(event.path("occurredAt").asText()).isNotBlank();
    }

    // ==== T22 幂等重放 → 仍 1 条（第二次提前返回，0 第二次调用）====

    @Test
    void idempotentReplayEmitsNoSecondEvent() throws Exception {
        postDelivery("idem-e2");
        postDelivery("idem-e2");

        assertThat(EVIDENCE.requests()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery", Integer.class))
                .isEqualTo(1);
    }

    // ==== T23 使用判定拒绝 → 交付不创建、0 事件（Q10）====

    @Test
    void deniedDeliveryEmitsNoEvent() throws Exception {
        STUB.respondCheckError("1008C0020", "越界使用被拒绝，触发要素见留痕", 403);

        final MvcResult result = postDelivery("idem-e3");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(EVIDENCE.requests()).isZero();
    }

    private MvcResult postDelivery(final String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/deliveries")
                .header("X-Ctds-Subject", REQUESTER)
                .header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"CO000001\",\"deliveryType\":\"FILE\","
                        + "\"fileId\":\"sample-dataset\",\"purpose\":\"风控建模\","
                        + "\"territory\":\"本市域\"}")).andReturn();
    }

    /** 存证内部写面桩（记录载荷；可切换响应）。 */
    private static final class EvidenceStub {

        private final HttpServer server;
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private volatile int status = 200;
        private volatile String response = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";

        EvidenceStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/evidence/internal/records", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            bodies.clear();
            status = 200;
            response = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";
        }

        int requests() {
            return bodies.size();
        }

        List<JsonNode> events() {
            return bodies.stream().map(body -> {
                try {
                    return MAPPER.readTree(body);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(final HttpExchange exchange) throws IOException {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
