package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.RecordingAuditRecorder;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestNanoClockConfiguration;
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
 * <p>限流阈值 = 3 次容量 + 1 次/秒补充；<b>令牌桶时间来源以 {@link TestNanoClockConfiguration}
 * 的可控时钟注入</b>（生产默认 {@code System::nanoTime} 不变）——"同一刻度内第 4 次被拒"与
 * "推进 1 秒后补充 1 个令牌"两条判定均为确定性断言，不依赖用例执行耗时（消除挂钟假红）。
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
@Import({RecordingAuditConfiguration.class, TestNanoClockConfiguration.class})
class GatewayRateLimitIntegrationTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    private static final String CONTRACT_NO = "CT-2026-0002";
    private static final String DELIVERY_PATH = "/api/v1/data-products/12";
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
        TestNanoClockConfiguration.NANOS.set(0L);
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
        assertThat(JUDGE.requestCount()).as("超限不调用判定（hifi §2 限流先于策略执行点）").isEqualTo(3);
        assertThat(meteringEvents()).as("超限不产生计量事件").hasSize(3);
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_DENIED)).hasSize(1);
    }

    @Test
    void refillRestoresOneTokenAfterClockAdvanceAndExhaustsAgain() {
        final String subject = "S-rate-refill";
        JUDGE.respondAllowed(subject, "S-prov-352");

        for (int i = 0; i < 3; i++) {
            delivery(HttpStatus.OK, subject);
        }
        assertThat(delivery(HttpStatus.TOO_MANY_REQUESTS, subject).path("code").asText())
                .as("同一时钟刻度内不补充 → 第 4 次被拒").isEqualTo(RATE_LIMITED_CODE);

        TestNanoClockConfiguration.NANOS.addAndGet(NANOS_PER_SECOND);
        assertThat(delivery(HttpStatus.OK, subject).path("code").asText())
                .as("时钟推进 1 秒 → 补充 1 个令牌 → 恢复放行").isEqualTo("0");
        assertThat(delivery(HttpStatus.TOO_MANY_REQUESTS, subject).path("code").asText())
                .as("补充的令牌被消耗后再次拒绝").isEqualTo(RATE_LIMITED_CODE);

        assertThat(UPSTREAM.requestCount()).as("放行 4 次触达上游 4 次").isEqualTo(4);
        assertThat(meteringEvents()).as("放行 4 次 ≡ 计量事件 4 条（拒绝不计量）").hasSize(4);
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
