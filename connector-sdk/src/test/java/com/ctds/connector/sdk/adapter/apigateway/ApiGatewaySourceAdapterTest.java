package com.ctds.connector.sdk.adapter.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * API 网关适配器（WBS-3.6.6 hifi §9 U4）：GET 只读、Authorization 原样出站、
 * 401 / 403 / 连接失败映射、读取摘要只含状态码与字节数（零响应内容）。
 */
class ApiGatewaySourceAdapterTest {

    private static final String AUTHORIZATION = "Bearer test-token-0001";
    private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "0123456789"));
        server.createContext("/unauthorized", exchange -> respond(exchange, 401, "{\"code\":\"denied\"}"));
        server.createContext("/forbidden", exchange -> respond(exchange, 403, "{\"code\":\"forbidden\"}"));
        server.createContext("/broken", exchange -> respond(exchange, 500, "{\"code\":\"boom\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetCapturedHeader() {
        LAST_AUTHORIZATION.set(null);
    }

    private static void respond(final HttpExchange exchange, final int status, final String body)
            throws IOException {
        LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static ApiGatewaySourceAdapter adapterWithCredential(final String credential) {
        final CredentialResolver resolver = ref -> credential;
        return new ApiGatewaySourceAdapter(resolver);
    }

    private static SourceRegistration registration(final String endpoint) {
        return new SourceRegistration(SourceType.API_GATEWAY, endpoint, "SC-test-ref", "CN000001");
    }

    private static SourceReadRequest readRequest() {
        return new SourceReadRequest("CT0000000001", "统计分析");
    }

    @Test
    void typeIsApiGateway() {
        assertThat(adapterWithCredential(AUTHORIZATION).type()).isEqualTo(SourceType.API_GATEWAY);
    }

    @Test
    void checkPassesAndSendsAuthorizationVerbatim() {
        final SourceCheckResult result =
                adapterWithCredential(AUTHORIZATION).check(registration(baseUrl + "/ok"));

        assertThat(result.passed()).isTrue();
        assertThat(LAST_AUTHORIZATION.get()).isEqualTo(AUTHORIZATION);
    }

    @Test
    void unauthorizedMapsToInvalidCredential() {
        assertThat(adapterWithCredential(AUTHORIZATION).check(registration(baseUrl + "/unauthorized"))
                .failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
    }

    @Test
    void forbiddenMapsToInsufficientPermission() {
        assertThat(adapterWithCredential(AUTHORIZATION).check(registration(baseUrl + "/forbidden"))
                .failureType()).isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
    }

    @Test
    void connectionRefusedMapsToNetworkUnreachable() {
        assertThat(adapterWithCredential(AUTHORIZATION).check(registration("http://127.0.0.1:1/api"))
                .failureType()).isEqualTo(SourceCheckFailureType.NETWORK_UNREACHABLE);
    }

    @Test
    void unexpectedStatusFailsLoud() {
        assertThatThrownBy(() -> adapterWithCredential(AUTHORIZATION).check(registration(baseUrl + "/broken")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API 网关探测应答异常");
    }

    @Test
    void readSummaryCarriesStatusAndBytesOnly() {
        final SourceReadResult result = adapterWithCredential(AUTHORIZATION)
                .read(registration(baseUrl + "/ok"), readRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("status=200;bytes=10;contentType=application/json");
        assertThat(result.summary()).doesNotContain("0123456789");
    }

    @Test
    void readUnauthorizedIsBusinessConclusion() {
        final SourceReadResult result = adapterWithCredential(AUTHORIZATION)
                .read(registration(baseUrl + "/unauthorized"), readRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).isEqualTo("读取失败：凭据无效");
    }

    @Test
    void credentialWithControlCharactersIsRejected() {
        final SourceCheckResult result =
                adapterWithCredential("Bearer token\r\nX-Injected: 1").check(registration(baseUrl + "/ok"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(LAST_AUTHORIZATION.get()).isNull();
    }
}
