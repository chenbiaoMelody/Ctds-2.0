package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * KmsKeyProvider 对 KMS 服务的供给行为（hifi §2，规格行为 1）：
 * 正常供给当前/历史版本；KMS 不可达、编号/版本不存在、响应不合法一律收敛 1001S0001（不静默降级）。
 * 桩 = JDK 内置 HttpServer（零新依赖）。
 */
class KmsKeyProviderTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/api/v1/keys/known", exchange -> {
            final String json = "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\",\"data\":{"
                    + "\"keyRef\":\"known\",\"status\":\"ENABLED\",\"currentVersion\":3}}";
            respond(exchange, 200, json);
        });
        server.createContext("/api/v1/keys/known/material", exchange -> {
            final String json = "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\",\"data\":{"
                    + "\"keyRef\":\"known\",\"version\":3,\"material\":\"" + base64(TestKeys.OTHER_KEY_16) + "\"}}";
            respond(exchange, 200, json);
        });
        server.createContext("/api/v1/keys/known/versions/3/material", exchange -> {
            final String json = "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\",\"data\":{"
                    + "\"keyRef\":\"known\",\"version\":3,\"material\":\"" + base64(TestKeys.KEY_16) + "\"}}";
            respond(exchange, 200, json);
        });
        server.createContext("/api/v1/keys/missing", exchange ->
                respond(exchange, 404, "{\"code\":\"1002B0002\",\"message\":\"密钥编号或版本不存在\","
                        + "\"traceId\":\"-\",\"data\":null}"));
        server.createContext("/api/v1/keys/broken/material", exchange ->
                respond(exchange, 200, "not-json"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static String base64(final byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status,
                                final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private KmsKeyProvider provider() {
        return new KmsKeyProvider(baseUrl, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
    }

    @Test
    void fetchesCurrentVersionMaterial() {
        final KmsKeyProvider provider = provider();
        assertThat(provider.currentVersion("known")).isEqualTo(3);
        assertThat(provider.sm4Key("known")).isEqualTo(TestKeys.OTHER_KEY_16);
    }

    @Test
    void fetchesHistoricalVersionMaterial() {
        assertThat(provider().sm4Key("known", 3)).isEqualTo(TestKeys.KEY_16);
    }

    @Test
    void unknownKeyRefIsKeyUnavailable() {
        final KmsKeyProvider provider = provider();
        assertThatThrownBy(() -> provider.currentVersion("missing"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
        assertThatThrownBy(() -> provider.sm4Key("missing", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }

    @Test
    void unreachableKmsIsKeyUnavailable() {
        // 连一个没有服务在听的端口（fail-fast 不降级）
        final KmsKeyProvider provider = new KmsKeyProvider("http://127.0.0.1:1",
                java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(200));
        assertThatThrownBy(() -> provider.sm4Key("known"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }

    @Test
    void malformedResponseIsKeyUnavailable() {
        assertThatThrownBy(() -> provider().sm4Key("broken"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }

    @Test
    void wrongMaterialLengthIsKeyUnavailable() {
        // 材料长度不是 16 字节 = KMS 响应不合法 → 1001S0001（不把坏密钥交给调用方）
        server.createContext("/api/v1/keys/short/material", exchange -> {
            final String json = "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\",\"data\":{"
                    + "\"keyRef\":\"short\",\"version\":1,\"material\":\"" + base64(new byte[8]) + "\"}}";
            respond(exchange, 200, json);
        });
        assertThatThrownBy(() -> provider().sm4Key("short"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }
}
