package com.ctds.did.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.did.domain.SubjectAdmission;
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
 * 主体状态只读客户端单元测试（WBS-3.1.9 hifi §5，JDK 内置 HttpServer 桩，零新增依赖）：
 * 服务身份头与只读角色、ADMITTED/NOT_ADMITTED 判定、未配置/非 200/业务码非 0/非 JSON 一律 UNAVAILABLE（不冒充不通过）。
 */
class SubjectStatusHttpClientTest {

    private static final String SUBJECT_NO = "S20260922000001";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedSubject = new AtomicReference<>();
    private final AtomicReference<String> capturedRoles = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/subject/internal/subjects/", this::handle);
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
        capturedSubject.set(exchange.getRequestHeaders().getFirst("X-Ctds-Subject"));
        capturedRoles.set(exchange.getRequestHeaders().getFirst("X-Ctds-Roles"));
        respond(exchange, 200, "{\"code\":\"0\",\"message\":\"success\",\"traceId\":\"-\","
                + "\"data\":{\"subjectNo\":\"" + SUBJECT_NO + "\",\"status\":\"ADMITTED\"}}");
    }

    @Test
    void admittedSubjectIsRecognizedWithServiceIdentityHeaders() {
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl, new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.ADMITTED);
        // 服务身份 + 专用只读角色（不冒充 reviewer/applicant）
        assertThat(capturedSubject.get()).isEqualTo("did-service");
        assertThat(capturedRoles.get()).isEqualTo("did-internal");
    }

    @Test
    void nonAdmittedStatusIsRecognized() {
        server.removeContext("/api/v1/subject/internal/subjects/");
        server.createContext("/api/v1/subject/internal/subjects/", exchange ->
                respond(exchange, 200, "{\"code\":\"0\",\"data\":{\"status\":\"PENDING_REVIEW\"}}"));
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void blankBaseUrlIsUnavailable() {
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient("",
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void nonHttp200IsUnavailable() {
        server.removeContext("/api/v1/subject/internal/subjects/");
        server.createContext("/api/v1/subject/internal/subjects/", exchange ->
                respond(exchange, 503, "upstream unavailable"));
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void nonZeroBizCodeIsUnavailable() {
        server.removeContext("/api/v1/subject/internal/subjects/");
        server.createContext("/api/v1/subject/internal/subjects/", exchange ->
                respond(exchange, 200, "{\"code\":\"1000C0001\",\"message\":\"参数不合法\"}"));
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void unknownSubjectIsNotAdmitted() {
        // 主体编号不存在（业务答复 1000C0003）= 绑定不成立（非"不可用"，hifi §5 实施修正）
        server.removeContext("/api/v1/subject/internal/subjects/");
        server.createContext("/api/v1/subject/internal/subjects/", exchange ->
                respond(exchange, 400, "{\"code\":\"1000C0003\",\"message\":\"申请编号不存在\"}"));
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.NOT_ADMITTED);
    }

    @Test
    void unparsableBodyIsUnavailable() {
        server.removeContext("/api/v1/subject/internal/subjects/");
        server.createContext("/api/v1/subject/internal/subjects/", exchange ->
                respond(exchange, 200, "not-json"));
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient(baseUrl,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    @Test
    void unreachableSubjectServiceIsUnavailable() {
        final int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        final SubjectStatusHttpClient client = new SubjectStatusHttpClient("http://127.0.0.1:" + port,
                new ObjectMapper());

        assertThat(client.check(SUBJECT_NO)).isEqualTo(SubjectAdmission.UNAVAILABLE);
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) {
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