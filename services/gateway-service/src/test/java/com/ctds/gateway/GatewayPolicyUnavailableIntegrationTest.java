package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.RecordingAuditRecorder;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 判定服务不可达 → fail-closed（hifi §9 T7 边界）：判定基址指向本机保留端口（无监听），
 * 断言 503 + {@code 1009S0001} + <b>上游零请求</b>（绝不因"判定不出来"而放行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=500ms"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayPolicyUnavailableIntegrationTest {

    /** 判定基址：未监听端口（连接立即被拒）。 */
    private static final String UNREACHABLE_JUDGE = "http://127.0.0.1:1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> UNREACHABLE_JUDGE);
    }

    @AfterAll
    static void stopStubs() {
        UPSTREAM.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AuditRecorder auditRecorder;

    @Test
    void unreachableJudgeFailsClosed() {
        UPSTREAM.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        ((RecordingAuditRecorder) auditRecorder).clear();

        final JsonNode body = readTree(WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build()
                .get().uri("/api/v1/data-products/12")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid("S-unreachable", List.of("provider")))
                .header("X-Ctds-Contract-No", "CT-2026-0003")
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                .expectBody().returnResult().getResponseBody());

        assertThat(body.path("code").asText()).isEqualTo("1009S0001");
        assertThat(UPSTREAM.requestCount()).as("判定不可达绝不触达数据源").isZero();
        final List<AuditEvent> metering =
                ((RecordingAuditRecorder) auditRecorder).eventsOf(GatewayAuditActions.DELIVERY_METERING);
        assertThat(metering).isEmpty();
    }

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
