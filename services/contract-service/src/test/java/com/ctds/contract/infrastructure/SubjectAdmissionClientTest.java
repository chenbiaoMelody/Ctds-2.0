package com.ctds.contract.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.SubjectAdmission;
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
 * 资格客户端三态映射单测（WBS-3.4.2 hifi §7 领域/客户端锚；JDK HttpServer 桩，
 * 沿 catalog SubjectAdmissionClientTest 先例）：ADMITTED 放行；未入驻与主体不存在同归
 * NOT_ADMITTED（统一文案防枚举在应用服务落）；不可达/读超时/非 200/非 0 码/解析失败/
 * 请求构造失败（畸形主体编号）/未配置 = UNAVAILABLE 不冒充资格拒绝。
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
        // 1000C0003 = 主体不存在：资格不成立（与未入驻同归 NOT_ADMITTED → 应用服务统一文案，防枚举）
        responseBody.set("{\"code\":\"1000C0003\",\"message\":\"申请编号不存在\"}");
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void notFoundAndNotAdmittedAreIndistinguishableAtClientBoundary() {
        // 防枚举同形（行为 1 规则 5）：主体不存在（1000C0003）与未入驻（业务状态非 ADMITTED）在
        // **客户端边界**归并为同一三态值 → 应用层只有一条拒绝分支，对外码与文案必然逐字相同
        responseBody.set("{\"code\":\"1000C0003\",\"message\":\"申请编号不存在\"}");
        final SubjectAdmission absent = client(baseUrl).check("S1");
        responseBody.set("{\"code\":\"0\",\"data\":{\"subjectNo\":\"S1\",\"status\":\"PENDING_REVIEW\"}}");
        final SubjectAdmission pending = client(baseUrl).check("S1");
        assertThat(absent).isEqualTo(pending).isEqualTo(SubjectAdmission.NOT_ADMITTED);
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
        assertThat(client("http://127.0.0.1:1").check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void readTimeoutMapsToUnavailable() {
        // 读超时（沿 catalog 契约：readTimeout 3s）——桩返回**合法 ADMITTED 体**但挂起超时节流：
        // 若读超时配置失守，则 4s 后拿到 200+ADMITTED → 本用例在结果与耗时两处必红（可证伪探针）
        responseBody.set("{\"code\":\"0\",\"data\":{\"subjectNo\":\"S1\",\"status\":\"ADMITTED\"}}");
        delayMillis.set(4000L);
        final long startNanos = System.nanoTime();
        assertThat(client(baseUrl).check("S1")).isEqualTo(SubjectAdmission.UNAVAILABLE);
        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        assertThat(elapsedMillis).as("应经读超时（约 3s）而非拿到 4s 后的完整响应").isBetween(2500L, 3900L);
    }

    @Test
    void malformedSubjectNoMapsToUnavailable() {
        // 主体标识含 URI 非法字符：请求构造失败同归 UNAVAILABLE（fail-closed，不上抛 500）
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
        assertThat(subjectHeaders.get()).containsExactly("contract-service");
        assertThat(roleHeaders.get()).containsExactly("contract-internal");
    }
}
