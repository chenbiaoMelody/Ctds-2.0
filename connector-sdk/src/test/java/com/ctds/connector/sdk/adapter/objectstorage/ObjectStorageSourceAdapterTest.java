package com.ctds.connector.sdk.adapter.objectstorage;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 对象存储适配器（WBS-3.6.6 hifi §9 U3）：预签名 URL 只读 GET、401 / 403 签名类 / 403 权限 / 404 映射、
 * 连接失败 → 网络不可达、读取摘要只含内容类型与大小（零内容）。
 */
class ObjectStorageSourceAdapterTest {

    private static final String ENDPOINT = "oss://dataset-bucket/data/2026";
    private static final AtomicInteger RANGE_REQUESTS = new AtomicInteger();

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "application/octet-stream",
                "payload", true));
        server.createContext("/unauthorized", exchange -> respond(exchange, 401, "text/plain", "no", false));
        server.createContext("/expired", exchange -> respond(exchange, 403, "application/xml",
                "<Error><Code>SignatureDoesNotMatch</Code></Error>", false));
        server.createContext("/forbidden", exchange -> respond(exchange, 403, "application/xml",
                "<Error><Code>AccessDenied</Code></Error>", false));
        server.createContext("/missing", exchange -> respond(exchange, 404, "text/plain", "no", false));
        server.createContext("/broken", exchange -> respond(exchange, 500, "text/plain", "boom", false));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(final HttpExchange exchange, final int status, final String contentType,
            final String body, final boolean range) throws IOException {
        if (range && exchange.getRequestHeaders().getFirst("Range") != null) {
            RANGE_REQUESTS.incrementAndGet();
        }
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(payload.length));
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static ObjectStorageSourceAdapter adapterWithCredential(final String credential) {
        final CredentialResolver resolver = ref -> credential;
        return new ObjectStorageSourceAdapter(resolver);
    }

    private static SourceRegistration registration() {
        return new SourceRegistration(SourceType.OBJECT_STORAGE, ENDPOINT, "SC-test-ref", "CN000001");
    }

    private static SourceReadRequest readRequest() {
        return new SourceReadRequest("CT0000000001", "统计分析");
    }

    @Test
    void typeIsObjectStorage() {
        assertThat(adapterWithCredential(baseUrl + "/ok").type()).isEqualTo(SourceType.OBJECT_STORAGE);
    }

    @Test
    void checkPassesWithRangeRequest() {
        final SourceCheckResult result = adapterWithCredential(baseUrl + "/ok").check(registration());

        assertThat(result.passed()).isTrue();
        assertThat(RANGE_REQUESTS.get()).isPositive();
    }

    @Test
    void unauthorizedMapsToInvalidCredential() {
        assertThat(adapterWithCredential(baseUrl + "/unauthorized").check(registration()).failureType())
                .isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
    }

    @Test
    void expiredSignatureMapsToInvalidCredential() {
        final SourceCheckResult result = adapterWithCredential(baseUrl + "/expired").check(registration());

        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(result.message()).isEqualTo("凭据无效");
    }

    @Test
    void signatureMismatchMapsToInvalidCredential() {
        assertThat(adapterWithCredential(baseUrl + "/expired?X-Amz-Signature=abc").check(registration())
                .message()).isEqualTo("凭据无效");
    }

    @Test
    void forbiddenWithoutSignatureMapsToInsufficientPermission() {
        assertThat(adapterWithCredential(baseUrl + "/forbidden").check(registration()).failureType())
                .isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
    }

    @Test
    void missingObjectMapsToInsufficientPermissionWithoutRevealingExistence() {
        final SourceCheckResult result = adapterWithCredential(baseUrl + "/missing").check(registration());

        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
        assertThat(result.message()).isEqualTo("权限不足");
    }

    @Test
    void connectionRefusedMapsToNetworkUnreachable() {
        assertThat(adapterWithCredential("http://127.0.0.1:1/object").check(registration()).failureType())
                .isEqualTo(SourceCheckFailureType.NETWORK_UNREACHABLE);
    }

    @Test
    void malformedCredentialIsBusinessFailure() {
        final SourceCheckResult result = adapterWithCredential("not-a-url").check(registration());

        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
    }

    @Test
    void readReturnsContentTypeAndSizeOnly() {
        final SourceReadResult result =
                adapterWithCredential(baseUrl + "/ok").read(registration(), readRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("contentType=application/octet-stream")
                .contains("size=7")
                .doesNotContain("payload");
    }

    @Test
    void unexpectedStatusFailsLoud() {
        assertThatThrownBy(() -> adapterWithCredential(baseUrl + "/broken").check(registration()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对象存储探测应答异常");
    }
}
