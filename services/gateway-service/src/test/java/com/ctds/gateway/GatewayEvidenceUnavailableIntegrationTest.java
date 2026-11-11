package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.TestTokens;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 存证上游不可达 → 绝不假成功（WBS-3.8.6 hifi §1 T9）：evidence 基址指向本机保留端口（无监听），
 * 经 {@code connector-evidence} 的概览请求必须是 5xx 且响应体不含成功码——**不得 200 假成功**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayEvidenceUnavailableIntegrationTest {

    /** 存证基址：取一个空闲端口后释放（未监听，连接立即被拒；沿 did / subject 同款先例）。 */
    private static final int UNREACHABLE_PORT = unusedPort();
    private static final String UNREACHABLE_EVIDENCE = "http://127.0.0.1:" + UNREACHABLE_PORT;

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new IllegalStateException("无法取得空闲端口", e);
        }
    }

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_EVIDENCE_BASEURL", () -> UNREACHABLE_EVIDENCE);
    }

    @LocalServerPort
    private int port;

    /** T9：上游不可达 → 5xx，不伪造 200 / 不伪造成功码。 */
    @Test
    void unreachableEvidenceUpstreamDoesNotFakeSuccess() {
        final EntityExchangeResult<String> result = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build()
                .get().uri("/api/v1/evidence/overview")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid("S-admin-evidence", List.of("admin")))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        final int status = result.getStatus().value();
        assertThat(status).as("上游不可达必须 5xx（实测状态码 %d）", status)
                .isBetween(500, 599);
        final String body = result.getResponseBody() == null ? ""
                : new String(result.getResponseBody().getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
        assertThat(body).as("不得返回成功码（不假成功）").doesNotContain("\"code\":\"0\"");
    }
}
