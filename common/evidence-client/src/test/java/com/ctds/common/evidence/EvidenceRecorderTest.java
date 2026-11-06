package com.ctds.common.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 存证提交客户端单元测试（WBS-3.8.3 hifi §1 / §8 T1~T5、T8；桩 = JDK HttpServer，零新依赖）。
 * 覆盖：无事务立即发送 / 事务内提交后发送 / 回滚零发送 / 失败不抛出 / 关闭与空地址跳过 / 六键载荷。
 */
class EvidenceRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVICE = "space-service";
    private static final String EVENT_DID = "did:ctds:abcdef123456";
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-06T14:12:00Z");

    private StubEvidenceServer server;
    private EvidenceClientProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubEvidenceServer();
        properties = new EvidenceClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(server.baseUrl());
        properties.setRoles("evidence-internal");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() {
        server.stop();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** T1：无事务调用 → 立即发送 1 次；请求体恰六键、无附加字段。 */
    @Test
    void sendsImmediatelyWithoutTransactionWithExactlySixKeys() throws IOException {
        recorder().record(event());

        assertThat(server.count()).isEqualTo(1);
        assertThat(server.lastSubject()).isEqualTo(SERVICE);
        assertThat(server.lastRoles()).isEqualTo("evidence-internal");
        final JsonNode body = MAPPER.readTree(server.lastBody());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("stageType").asText()).isEqualTo("AUTHORIZATION");
        assertThat(body.path("businessNo").asText()).isEqualTo("SPACE12:ADMISSION34");
        assertThat(body.path("operationType").asText()).isEqualTo("SPACE_MEMBER_ADMITTED");
        assertThat(body.path("subjectDid").asText()).isEqualTo(EVENT_DID);
        assertThat(body.path("occurredAt").asText()).isEqualTo("2026-11-06T14:12:00Z");
        assertThat(body.path("actorSubjectNo").asText()).isEqualTo("S20261106000001");
    }

    /** T2：事务内调用 → 提交前 0 请求、提交后 1 请求。 */
    @Test
    void sendsOnlyAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            recorder().record(event());
            assertThat(server.count()).isZero();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(server.count()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** T3：事务回滚 → 0 请求（afterCommit 不执行）。 */
    @Test
    void sendsNothingWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            recorder().record(event());
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(server.count()).isZero();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** T4：存证服务 500 → 不抛出（业务响应不受影响）。 */
    @Test
    void swallowsServerError() {
        server.respond(500, "{\"code\":\"1012S0001\",\"message\":\"unavailable\"}");

        assertThatCode(() -> recorder().record(event())).doesNotThrowAnyException();
        assertThat(server.count()).isEqualTo(1);
    }

    /** T4-补：非 200 + code 非 0 的 200 响应同样视为失败且不抛。 */
    @Test
    void swallowsBusinessErrorCode() {
        server.respond(200, "{\"code\":\"1012C0002\",\"message\":\"参数不合法\"}");

        assertThatCode(() -> recorder().record(event())).doesNotThrowAnyException();
        assertThat(server.count()).isEqualTo(1);
    }

    /** T5：base-url 空白 → 0 请求、不抛出。 */
    @Test
    void skipsWhenBaseUrlBlank() {
        properties.setBaseUrl("");

        assertThatCode(() -> recorder().record(event())).doesNotThrowAnyException();
        assertThat(server.count()).isZero();
    }

    /** T5-补：enabled=false → 0 请求、不抛出。 */
    @Test
    void skipsWhenDisabled() {
        properties.setEnabled(false);

        assertThatCode(() -> recorder().record(event())).doesNotThrowAnyException();
        assertThat(server.count()).isZero();
    }

    /** T8：afterCommit 回调内异常被捕获（事务已提交，业务响应不受影响）。 */
    @Test
    void afterCommitFailureDoesNotPropagate() {
        server.respond(500, "{\"code\":\"1012S0001\",\"message\":\"unavailable\"}");
        TransactionSynchronizationManager.initSynchronization();
        try {
            recorder().record(event());
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
            assertThat(server.count()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private EvidenceRecorder recorder() {
        return new EvidenceRecorder(properties, SERVICE);
    }

    private static EvidenceEvent event() {
        return new EvidenceEvent("AUTHORIZATION", "SPACE12:ADMISSION34", "SPACE_MEMBER_ADMITTED",
                EVENT_DID, OCCURRED_AT, "S20261106000001");
    }

    /** 存证内部写面桩（记录次数 / 请求体 / 身份头；可切换响应）。 */
    private static final class StubEvidenceServer {

        private final HttpServer server;
        private final AtomicInteger count = new AtomicInteger();
        private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
        private volatile String lastSubject = "";
        private volatile String lastRoles = "";
        private volatile int status = 200;
        private volatile String responseBody = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";

        StubEvidenceServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/evidence/internal/records", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int count() {
            return count.get();
        }

        String lastBody() {
            return bodies.isEmpty() ? "" : bodies.get(bodies.size() - 1);
        }

        String lastSubject() {
            return lastSubject;
        }

        String lastRoles() {
            return lastRoles;
        }

        void respond(final int responseStatus, final String body) {
            this.status = responseStatus;
            this.responseBody = body;
        }

        void stop() {
            server.stop(0);
        }

        private void handle(final HttpExchange exchange) throws IOException {
            count.incrementAndGet();
            lastSubject = exchange.getRequestHeaders().getFirst("X-Ctds-Subject");
            lastRoles = exchange.getRequestHeaders().getFirst("X-Ctds-Roles");
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
