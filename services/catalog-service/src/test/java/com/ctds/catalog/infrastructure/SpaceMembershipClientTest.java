package com.ctds.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.catalog.domain.SpaceMembership;
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
 * 空间成员客户端三态映射单测（WBS-3.3.2 hifi §5 / T12；JDK HttpServer 桩，沿 space
 * SubjectAdmissionClientTest 先例）：可用判定（spaceStatus + role|NONE）；空间不存在同形
 * （200 + NONE/NONE 按可用判定返回，登记侧判 1007C0002）；不可达/非 200/非 0 码/解析失败 =
 * UNAVAILABLE（不冒充"非成员/空间不存在"）。
 */
class SpaceMembershipClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<String> requestPath = new AtomicReference<>("");
    private final AtomicReference<List<String>> subjectHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> roleHeaders = new AtomicReference<>(List.of());

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/data-spaces/internal", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
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
        final byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private SpaceMembershipClient client(final String base) {
        return new SpaceMembershipClient(base, MAPPER);
    }

    @Test
    void activeMemberParsed() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"spaceStatus\":\"ACTIVE\",\"role\":\"MEMBER\"}}");
        final SpaceMembership membership = client(baseUrl).check(9001L, "S1");
        assertThat(membership.available()).isTrue();
        assertThat(membership.isSpaceActive()).isTrue();
        assertThat(membership.isMember()).isTrue();
        assertThat(requestPath.get()).isEqualTo("/api/v1/data-spaces/internal/9001/memberships/S1");
    }

    @Test
    void nonMemberAndFrozenSpaceParsedForApplicationGate() {
        // 非成员（role NONE）与冻结空间（FROZEN）：客户端如实透传，门槛判定在应用服务
        responseBody.set("{\"code\":\"0\",\"data\":{\"spaceStatus\":\"ACTIVE\",\"role\":\"NONE\"}}");
        assertThat(client(baseUrl).check(9001L, "S1").isMember()).isFalse();
        responseBody.set("{\"code\":\"0\",\"data\":{\"spaceStatus\":\"FROZEN\",\"role\":\"OWNER\"}}");
        final SpaceMembership frozen = client(baseUrl).check(9001L, "S1");
        assertThat(frozen.available()).isTrue();
        assertThat(frozen.isSpaceActive()).isFalse();
    }

    @Test
    void spaceNotFoundSameShapeAnswer() {
        // 空间不存在 → 200 + NONE/NONE（防枚举同形；登记侧据此判 1007C0002）
        responseBody.set("{\"code\":\"0\",\"data\":{\"spaceStatus\":\"NONE\",\"role\":\"NONE\"}}");
        final SpaceMembership membership = client(baseUrl).check(9999L, "S1");
        assertThat(membership.available()).isTrue();
        assertThat(membership.isSpaceActive()).isFalse();
        assertThat(membership.isMember()).isFalse();
    }

    @Test
    void httpFailureMapsToUnavailable() {
        responseStatus.set(500);
        assertThat(client(baseUrl).check(9001L, "S1").available()).isFalse();
    }

    @Test
    void nonZeroBusinessCodeMapsToUnavailable() {
        responseBody.set("{\"code\":\"1006C0004\",\"message\":\"空间不存在\"}");
        assertThat(client(baseUrl).check(9001L, "S1").available()).isFalse();
    }

    @Test
    void malformedBodyMapsToUnavailable() {
        responseBody.set("not-json");
        assertThat(client(baseUrl).check(9001L, "S1").available()).isFalse();
    }

    @Test
    void unreachableServiceMapsToUnavailable() {
        assertThat(client("http://127.0.0.1:1").check(9001L, "S1").available()).isFalse();
    }

    @Test
    void blankBaseUrlIsUnavailableWithoutCall() {
        assertThat(client("").check(9001L, "S1").available()).isFalse();
    }

    @Test
    void sendsServiceIdentityHeaders() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"spaceStatus\":\"ACTIVE\",\"role\":\"MEMBER\"}}");
        client(baseUrl).check(9001L, "S-01");
        assertThat(subjectHeaders.get()).containsExactly("catalog-service");
        assertThat(roleHeaders.get()).containsExactly("catalog-internal");
    }
}
