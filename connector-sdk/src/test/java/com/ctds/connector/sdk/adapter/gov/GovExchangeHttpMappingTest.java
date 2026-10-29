package com.ctds.connector.sdk.adapter.gov;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 政务适配器 HTTP 应答映射用例（WBS-3.6.5 评审循环 1 修复批：补 hifi §7-⑤ 五条契约分支）：
 * 2xx / 401 / 403 / 其它非 2xx / 空应答，以及凭据确实以 Bearer 出站但不回显。
 *
 * <p>零外网、零新依赖：JDK 内置 {@link HttpServer} 绑定回环随机端口承载桩应答。</p>
 */
class GovExchangeHttpMappingTest {

    private static final String CREDENTIAL = "gov-exchange-secret-0001";
    private static final String CREDENTIAL_REF = "SC0123456789abcdef0123456789abcdef";
    private static final String CONNECTOR_NO = "CN000001";
    private static final String CONTRACT_NO = "CT0000000001";
    private static final String JSON_OK = "{\"rows\":1,\"code\":\"OK\",\"message\":\"模拟交换报文 1 件\"}";
    private static final String CONTEXT = "/gov";

    private static final AtomicReference<Integer> SERVER_STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> SERVER_BODY = new AtomicReference<>(JSON_OK);
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    private static HttpServer server;
    private static String baseUrl;

    private GovExchangeSourceAdapter adapter;

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() throws IOException {
        serve(200, JSON_OK);
        LAST_AUTH.set(null);
        if (server == null) {
            startServer();
        }
        adapter = new GovExchangeSourceAdapter(ref -> CREDENTIAL, Duration.ofSeconds(2));
        adapter.mockResetCounters();
    }

    @Test
    void maps2xxToPassed() {
        final SourceCheckResult result = adapter.check(registration());
        assertThat(result.passed()).isTrue();
        assertThat(result.failureType()).isNull();
        assertThat(LAST_AUTH.get()).isEqualTo("Bearer " + CREDENTIAL);
    }

    @Test
    void maps401ToInvalidCredential() {
        serve(401, "{\"code\":\"INVALID_CREDENTIAL\"}");
        final SourceCheckResult result = adapter.check(registration());
        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(result.message()).isEqualTo("凭据无效");
    }

    @Test
    void maps403ToInsufficientPermission() {
        serve(403, "{\"code\":\"INSUFFICIENT_PERMISSION\"}");
        final SourceCheckResult result = adapter.check(registration());
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
        assertThat(result.message()).isEqualTo("权限不足");
    }

    @Test
    void mapsOtherStatusToUnhandledFailure() {
        serve(500, "{\"code\":\"OK\"}");
        assertThatThrownBy(() -> adapter.check(registration()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("应答状态异常");
    }

    /**
     * 空应答 = 非预期（上抛 → 平台 502）。
     *
     * <p>DB-87 修复：空应答改用<b>独立桩实例</b>（不再共享 {@code SERVER_STATUS / SERVER_BODY} 静态态），
     * 消除"双模块联跑偶发读到上一用例报文"的竞态；断言口径零变化（仍要求 {@code IllegalStateException}
     * + "应答为空"）。</p>
     *
     * <p>DB-87 残余（WBS-3.6.6 门禁第三发 RED 归因）：Windows 回环高频建连偶发<b>立即</b>失败
     * （门禁实测该用例 0.019s 内未抛、另一共享桩用例连接耗时 0.9s）——{@code check} 按生产语义把
     * {@code ResourceAccessException} 映射为 NETWORK_UNREACHABLE 正常返回，与"空应答被错误映射"
     * 在原断言下不可判别。处置：NETWORK_UNREACHABLE 视为基础设施偶发并重试（≤5 次，间隔 100ms）；
     * 其余映射结果（含通过）立即判红并给出映射结果，空应答映射契约本身（异常类型 + 文案）不变。</p>
     */
    @Test
    void mapsEmptyBodyToUnhandledFailure() throws Exception {
        final HttpServer emptyBodyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        emptyBodyServer.createContext(CONTEXT, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        emptyBodyServer.start();
        try {
            final SourceRegistration emptyBody = new SourceRegistration(SourceType.GOV_EXCHANGE,
                    "http://127.0.0.1:" + emptyBodyServer.getAddress().getPort() + CONTEXT,
                    CREDENTIAL_REF, CONNECTOR_NO);
            IllegalStateException emptyAnswer = null;
            for (int attempt = 0; attempt < 5 && emptyAnswer == null; attempt++) {
                try {
                    final SourceCheckResult result = adapter.check(emptyBody);
                    if (result.passed()
                            || result.failureType() != SourceCheckFailureType.NETWORK_UNREACHABLE) {
                        throw new AssertionError("空应答被映射为 passed=" + result.passed()
                                + " failureType=" + result.failureType()
                                + " message=" + result.message());
                    }
                    Thread.sleep(100);
                } catch (final IllegalStateException e) {
                    emptyAnswer = e;
                }
            }
            assertThat(emptyAnswer).isNotNull();
            assertThat(emptyAnswer).hasMessageContaining("应答为空");
        } finally {
            emptyBodyServer.stop(0);
        }
    }

    @Test
    void readFailureCarriesReadableSummaryWithoutCredential() {
        serve(401, "{\"code\":\"INVALID_CREDENTIAL\"}");
        final SourceReadResult result = adapter.read(registration(),
                new SourceReadRequest(CONTRACT_NO, "统计分析"));
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("凭据无效");
        assertThat(result.summary()).doesNotContain(CREDENTIAL);
    }

    @Test
    void readSuccessSummaryComesFromResponseAndOmitsCredential() {
        final SourceReadResult result = adapter.read(registration(),
                new SourceReadRequest(CONTRACT_NO, "统计分析"));
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("模拟交换报文 1 件");
        assertThat(result.summary()).doesNotContain(CREDENTIAL);
    }

    @Test
    void readSummaryNeverEchoesCredentialOrControlChars() {
        serve(200, "{\"rows\":1,\"code\":\"OK\",\"message\":\"echo " + CREDENTIAL + " \\n second-line\"}");
        final SourceReadResult result = adapter.read(registration(),
                new SourceReadRequest(CONTRACT_NO, "统计分析"));
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).doesNotContain(CREDENTIAL).doesNotContain("\n").contains("****");
    }

    @Test
    void checkFailureMessageNeverEchoesCredential() {
        serve(200, "{\"code\":\"INVALID_CREDENTIAL\",\"message\":\"bad token " + CREDENTIAL + "\"}");
        final SourceCheckResult result = adapter.check(registration());
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(result.message()).doesNotContain(CREDENTIAL).contains("****");
    }

    @Test
    void longUpstreamMessageIsTruncated() {
        serve(200, "{\"rows\":1,\"code\":\"OK\",\"message\":\"" + "x".repeat(300) + "\"}");
        final SourceReadResult result = adapter.read(registration(),
                new SourceReadRequest(CONTRACT_NO, "统计分析"));
        assertThat(result.summary().length()).isLessThanOrEqualTo(120);
    }

    private static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CONTEXT, exchange -> {
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            final byte[] payload = SERVER_BODY.get().getBytes(UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(SERVER_STATUS.get(), -1);
            } else {
                exchange.sendResponseHeaders(SERVER_STATUS.get(), payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + CONTEXT;
    }

    private static void serve(final int status, final String body) {
        SERVER_STATUS.set(status);
        SERVER_BODY.set(body);
    }

    private static SourceRegistration registration() {
        return new SourceRegistration(SourceType.GOV_EXCHANGE, baseUrl, CREDENTIAL_REF, CONNECTOR_NO);
    }
}
