package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.config.ConnectorProperties;
import com.ctds.connector.domain.CurrentDid;
import com.ctds.connector.domain.SubjectAdmission;
import com.ctds.connector.infrastructure.DidStatusClient;
import com.ctds.connector.infrastructure.SubjectAdmissionClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 跨域依赖客户端直测（WBS-3.6.8 W8 / DB-84，hifi §3 T7 组）：SubjectAdmissionClient /
 * DidStatusClient 在可控 stub（500 / 超时 / 不可达 / 非正常体）下的降级分支直测——
 * 未配置 / 不可达 / 超时 / 非 200 / 不可解析一律降级（UNAVAILABLE），不冒充业务结论（fail-closed）；
 * 另含启动类无数据源配置 fail-fast 直测（ConnectorApplication 覆盖债）。
 */
class ConnectorDependencyClientsFailureTest {

    private static HttpServer stub;
    private static String baseUrl;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/admit", exchange -> respond(exchange, 500, "{\"code\":\"500\"}"));
        stub.createContext("/admitted", exchange -> respond(exchange, 200,
                "{\"code\":\"0\",\"data\":{\"status\":\"ADMITTED\"}}"));
        stub.createContext("/notfound", exchange -> respond(exchange, 200,
                "{\"code\":\"1000C0003\",\"message\":\"资源不存在\"}"));
        stub.createContext("/garbage", exchange -> respond(exchange, 200, "not-json"));
        stub.createContext("/slow", exchange -> {
            try {
                Thread.sleep(800);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"status\":\"ADMITTED\"}}");
        });
        stub.createContext("/did-active", exchange -> respond(exchange, 200,
                "{\"code\":\"0\",\"data\":{\"status\":\"ACTIVE\",\"did\":\"did:ctds:t.1\"}}"));
        stub.createContext("/did-revoked", exchange -> respond(exchange, 200,
                "{\"code\":\"0\",\"data\":{\"status\":\"REVOKED\",\"did\":\"did:ctds:t.1\"}}"));
        stub.createContext("/did-500", exchange -> respond(exchange, 500, "{\"code\":\"500\"}"));
        stub.createContext("/did-unknown", exchange -> respond(exchange, 200,
                "{\"code\":\"0\",\"data\":{\"status\":\"WEIRD\"}}"));
        stub.createContext("/did-slow", exchange -> {
            try {
                Thread.sleep(800);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"status\":\"ACTIVE\"}}");
        });
        stub.start();
        baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status,
            final String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static ConnectorProperties properties(final String url, final Duration timeout) {
        final ConnectorProperties props = new ConnectorProperties();
        props.getSubject().setBaseUrl(url);
        props.getSubject().setTimeout(timeout);
        props.getDid().setBaseUrl(url);
        props.getDid().setTimeout(timeout);
        return props;
    }

    @Test
    void admissionHappyPathMapsAdmitted() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties(baseUrl + "/admitted", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.ADMITTED);
    }

    @Test
    void admissionSubjectNotFoundMapsNotAdmitted() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties(baseUrl + "/notfound", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void admissionServer500DegradesToUnavailable() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties(baseUrl + "/admit", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void admissionGarbageBodyDegradesToUnavailable() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties(baseUrl + "/garbage", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void admissionTimeoutDegradesToUnavailable() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties(baseUrl + "/slow", Duration.ofMillis(50)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void admissionUnreachableDegradesToUnavailable() {
        // 关闭端口 = 连接拒绝（不可达分支；连接超时兜底 2s 内确定失败）
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties("http://127.0.0.1:1/admit", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void admissionBlankBaseUrlFailsClosed() {
        final SubjectAdmissionClient client = new SubjectAdmissionClient(
                properties("", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.check("S20261026000091")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void didActiveMapsToActive() {
        final DidStatusClient client = new DidStatusClient(
                properties(baseUrl + "/did-active", Duration.ofSeconds(2)), objectMapper());
        final CurrentDid did = client.current("S20261026000091");
        assertThat(did.active()).isTrue();
        assertThat(did.status()).isEqualTo("ACTIVE");
    }

    @Test
    void didRevokedMapsToInactive() {
        final DidStatusClient client = new DidStatusClient(
                properties(baseUrl + "/did-revoked", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.current("S20261026000091").active()).isFalse();
    }

    @Test
    void didServer500DegradesToUnavailable() {
        final DidStatusClient client = new DidStatusClient(
                properties(baseUrl + "/did-500", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.current("S20261026000091").isUnavailable()).isTrue();
    }

    @Test
    void didUnknownStatusDegradesToUnavailable() {
        final DidStatusClient client = new DidStatusClient(
                properties(baseUrl + "/did-unknown", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.current("S20261026000091").isUnavailable()).isTrue();
    }

    @Test
    void didTimeoutDegradesToUnavailable() {
        final DidStatusClient client = new DidStatusClient(
                properties(baseUrl + "/did-slow", Duration.ofMillis(50)), objectMapper());
        assertThat(client.current("S20261026000091").isUnavailable()).isTrue();
    }

    @Test
    void didUnreachableDegradesToUnavailable() {
        final DidStatusClient client = new DidStatusClient(
                properties("http://127.0.0.1:1/did-active", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.current("S20261026000091").isUnavailable()).isTrue();
    }

    @Test
    void didBlankBaseUrlFailsClosed() {
        final DidStatusClient client = new DidStatusClient(
                properties("", Duration.ofSeconds(2)), objectMapper());
        assertThat(client.current("S20261026000091").isUnavailable()).isTrue();
    }

    @Test
    void applicationFailsFastWithoutReachableDataSource() {
        // 启动类直测（覆盖债）：数据源不可达时启动 fail-fast（fail-closed 启动语义），
        // 不以"启动成功"静默降级
        assertThatThrownBy(() -> ConnectorApplication.main(new String[] {
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/ctds_none",
                "--spring.datasource.username=no-such",
                "--spring.datasource.password=no-such",
                "--server.port=0"}))
                .as("数据源不可达 = 启动失败（fail-closed）")
                .isInstanceOf(RuntimeException.class);
    }

    private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
