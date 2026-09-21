package com.ctds.did.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DID → KMS HTTP 客户端单元测试（JDK 内置 HttpServer 桩，零新增依赖）：
 * 服务身份头、请求体、成功解析、非 0 业务码、base-url 未配置、KMS 不可达。
 */
class DidKmsHttpClientTest {

    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedSubject = new AtomicReference<>();
    private final AtomicReference<String> capturedRoles = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/key-pairs", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        capturedSubject.set(exchange.getRequestHeaders().getFirst("X-Ctds-Subject"));
        capturedRoles.set(exchange.getRequestHeaders().getFirst("X-Ctds-Roles"));
        respond(exchange, 200, "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\","
                + "\"data\":{\"keyRef\":\"did-S1-1\",\"publicKeyHex\":\"" + PUBLIC_KEY_HEX
                + "\",\"createdAt\":\"2026-09-21T12:00:00\"}}");
    }

    @Test
    void createKeyPairPostsWithServiceIdentityAndReturnsPublicKeyHex() {
        final DidKmsHttpClient client = new DidKmsHttpClient(baseUrl, new ObjectMapper());

        final String publicKeyHex = client.createKeyPair("did-S1-1");

        assertThat(publicKeyHex).isEqualTo(PUBLIC_KEY_HEX);
        assertThat(capturedBody.get()).isEqualTo("{\"keyRef\":\"did-S1-1\"}");
        assertThat(capturedSubject.get()).isEqualTo("did-service");
        assertThat(capturedRoles.get()).isEqualTo("admin");
    }

    @Test
    void createKeyPairThrowsOnNonZeroCode() {
        server.removeContext("/api/v1/key-pairs");
        server.createContext("/api/v1/key-pairs", exchange ->
                respond(exchange, 400, "{\"code\":\"1002B0001\",\"message\":\"密钥编号已存在\",\"traceId\":\"-\"}"));
        final DidKmsHttpClient client = new DidKmsHttpClient(baseUrl, new ObjectMapper());

        assertThatThrownBy(() -> client.createKeyPair("did-S1-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1002B0001");
    }

    @Test
    void createKeyPairThrowsWhenBaseUrlNotConfigured() {
        final DidKmsHttpClient client = new DidKmsHttpClient("", new ObjectMapper());

        assertThatThrownBy(() -> client.createKeyPair("did-S1-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void createKeyPairThrowsWhenKmsUnreachable() {
        final int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        final DidKmsHttpClient client = new DidKmsHttpClient("http://127.0.0.1:" + port, new ObjectMapper());

        assertThatThrownBy(() -> client.createKeyPair("did-S1-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS 不可达");
    }

    private static void respond(final HttpExchange exchange, final int status,
            final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (final IOException e) {
            throw new IllegalStateException("测试桩响应失败", e);
        }
    }
}
