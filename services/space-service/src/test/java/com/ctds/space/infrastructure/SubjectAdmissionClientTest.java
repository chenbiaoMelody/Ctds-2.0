package com.ctds.space.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.space.domain.SubjectAdmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资格客户端三态映射单测（WBS-3.2.3 hifi §4 / T2 客户端侧；JDK HttpServer 桩，沿 did
 * SubjectStatusHttpClientTest 先例）：ADMITTED 放行；未入驻与主体不存在同归 NOT_ADMITTED
 * （统一文案防枚举在应用服务落）；不可达/读超时/非 200/非 0 码/解析失败/请求构造失败（畸形主体
 * 编号）/未配置 = UNAVAILABLE 不冒充资格拒绝。
 */
class SubjectAdmissionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<List<String>> subjectHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> roleHeaders = new AtomicReference<>(List.of());
    /** 桩响应延迟（毫秒）——读超时用例把响应挂起超过客户端 3s 读超时。 */
    private final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/subject/internal/subjects", exchange -> {
            subjectHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Subject"));
            roleHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Roles"));
            respond(exchange);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void respond(final HttpExchange exchange) throws IOException {
        final long delay = delayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private SubjectAdmissionClient client(final String base) {
        return new SubjectAdmissionClient(base, MAPPER);
    }

    @Test
    void admittedPassesThrough() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"subjectNo\":\"S1\",\"status\":\"ADMITTED\"}}");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.ADMITTED);
    }

    @Test
    void nonAdmittedStatusMapsToNotAdmitted() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"status\":\"PENDING_REVIEW\"}}");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void subjectNotFoundBusinessAnswerMapsToNotAdmitted() {
        // 1000C0003 = 主体不存在：资格不成立（与未入驻同归 NOT_ADMITTED → 应用服务统一文案 1006C0001）
        responseBody.set("{\"code\":\"1000C0003\",\"message\":\"申请编号不存在\"}");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void httpFailureMapsToUnavailable() {
        responseStatus.set(500);
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void nonZeroBusinessCodeMapsToUnavailable() {
        responseBody.set("{\"code\":\"1000S9999\",\"message\":\"系统繁忙\"}");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void malformedBodyMapsToUnavailable() {
        responseBody.set("not-json");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void unreachableServiceMapsToUnavailable() {
        // 未监听端口：不可达 → UNAVAILABLE（不冒充"未入驻"）
        assertThat(client("http://127.0.0.1:1").check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void readTimeoutMapsToUnavailable() {
        // 读超时（hifi §4 超时 3s 口径；评审循环 1 补锚——此前 READ_TIMEOUT 常量失守不会有任何红灯）
        delayMillis.set(4000L);
        final long startNanos = System.nanoTime();
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertThat(elapsedMillis).as("应经读超时（约 3s）而非立即失败").isBetween(2500L, 8000L);
    }

    @Test
    void malformedSubjectNoMapsToUnavailable() {
        // 主体标识含 URI 非法字符：请求构造失败同归 UNAVAILABLE（评审循环 1 安全补——曾出站 500）
        assertThat(client(baseUrl).check("a b")).isEqualTo(SubjectAdmission.UNAVAILABLE);
        assertThat(client(baseUrl).check("100%")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void blankBaseUrlIsUnavailableWithoutCall() {
        assertThat(client("").check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
        assertThat(client("   ").check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void sendsServiceIdentityHeaders() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"status\":\"ADMITTED\"}}");
        client(baseUrl).check("S-01");
        assertThat(subjectHeaders.get()).containsExactly("space-service");
        assertThat(roleHeaders.get()).containsExactly("space-internal");
    }
}
