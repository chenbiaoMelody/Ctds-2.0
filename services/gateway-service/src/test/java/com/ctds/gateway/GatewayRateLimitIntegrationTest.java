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
import org.junit.jupiter.api.BeforeEach;
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
 * 限流与判定不可用（fail-closed）真链集成测试（hifi §9：T4 限流双向 / T7 判定超时）。
 *
 * <p>限流阈值 = 3 次容量 + 1 次/秒补充（用例在同一秒内连打，故第 4 次必被拒）；
 * 判定超时阈值 300ms，判定 stub 延迟 1000ms → 必须 fail-closed（503 + 1009S0001，不触达数据源）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=true",
            "ctds.gateway.rate-limit.default-capacity=3",
            "ctds.gateway.rate-limit.default-refill-per-second=1",
            "ctds.gateway.policy.timeout=300ms"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayRateLimitIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    private static final String CONTRACT_NO = "CT-2026-0002";
    private static final String DELIVERY_PATH = "/api/v1/catalog/products/12";
    private static final String RATE_LIMITED_CODE = "1009C0002";
    private static final String UNAVAILABLE_CODE = "1009S0001";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> JUDGE.baseUrl());
    }

    @AfterAll
    static void stopStubs() {
        UPSTREAM.close();
        JUDGE.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AuditRecorder auditRecorder;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        UPSTREAM.resetCounters();
        JUDGE.resetCounters();
        UPSTREAM.setDelayMillis(0L);
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\",\"data\":\"probe\"}");
        JUDGE.setDelayMillis(0L);
        recorder().clear();
    }

    // ==== T4 限流（双向）====

    @Test
    void requestsWithinThresholdAreForwardedAndMetered() {
        final String subject = "S-rate-in";
        JUDGE.respondAllowed(subject, "S-prov-352");

        for (int i = 0; i < 3; i++) {
            assertThat(delivery(HttpStatus.OK, subject).path("code").asText()).isEqualTo("0");
        }

        assertThat(UPSTREAM.requestCount()).isEqualTo(3);
        assertThat(meteringEvents()).hasSize(3);
    }

    @Test
    void requestOverThresholdIsRejectedWithoutUpstreamOrMetering() {
        final String subject = "S-rate-out";
        JUDGE.respondAllowed(subject, "S-prov-352");

        for (int i = 0; i < 3; i++) {
            delivery(HttpStatus.OK, subject);
        }
        final var over = client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(subject,
                        List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        over.expectHeader().exists(HttpHeaders.RETRY_AFTER);
        final JsonNode body = readTree(over.expectBody().returnResult().getResponseBody());
        assertThat(body.path("code").asText()).isEqualTo(RATE_LIMITED_CODE);
        assertThat(UPSTREAM.requestCount()).as("超限不触达上游").isEqualTo(3);
        assertThat(meteringEvents()).as("超限不产生计量事件").hasSize(3);
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_DENIED)).hasSize(1);
    }

    // ==== T7 判定超时 → fail-closed ====

    @Test
    void policyTimeoutFailsClosedWithoutUpstream() {
        final String subject = "S-timeout";
        JUDGE.respondAllowed(subject, "S-prov-352");
        JUDGE.setDelayMillis(1000L);

        final JsonNode body = delivery(HttpStatus.SERVICE_UNAVAILABLE, subject);

        assertThat(body.path("code").asText()).as("判定超时 → fail-closed，不放行")
                .isEqualTo(UNAVAILABLE_CODE);
        assertThat(UPSTREAM.requestCount()).as("判定不可用不触达数据源").isZero();
        assertThat(meteringEvents()).isEmpty();
        assertThat(JUDGE.requestCount()).as("判定请求已发出（超时在响应侧）").isEqualTo(1);
    }

    // ==== 辅助 ====

    private JsonNode delivery(final HttpStatus expected, final String subject) {
        return readTree(client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(subject,
                        List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isEqualTo(expected.value())
                .expectBody().returnResult().getResponseBody());
    }

    private RecordingAuditRecorder recorder() {
        return (RecordingAuditRecorder) auditRecorder;
    }

    private List<AuditEvent> meteringEvents() {
        return recorder().eventsOf(GatewayAuditActions.DELIVERY_METERING);
    }

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
