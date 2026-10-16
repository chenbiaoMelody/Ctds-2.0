package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.RecordingAuditRecorder;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestTokens;
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
 * 限流开关负向（hifi §9 T4 反向）：{@code ctds.gateway.rate-limit.enabled=false} 时即使超过配置容量
 * （3）也全部放行——限流是网络级保护开关，关闭后不得残留任何拦截。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.rate-limit.default-capacity=3",
            "ctds.gateway.rate-limit.default-refill-per-second=1",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayRateLimitDisabledIntegrationTest {

    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

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

    @Test
    void rateLimitDisabledForwardsBurstBeyondThreshold() {
        UPSTREAM.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        JUDGE.respondAllowed("S-burst", "S-prov-352");
        ((RecordingAuditRecorder) auditRecorder).clear();

        final WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build();
        for (int i = 0; i < 5; i++) {
            client.get().uri("/api/v1/catalog/products/12")
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + TestTokens.valid("S-burst", List.of("provider")))
                    .header("X-Ctds-Contract-No", "CT-2026-0005")
                    .exchange().expectStatus().isOk();
        }

        assertThat(UPSTREAM.requestCount()).as("开关关闭时不限流，全部放行").isEqualTo(5);
        final List<AuditEvent> metering = ((RecordingAuditRecorder) auditRecorder)
                .eventsOf(GatewayAuditActions.DELIVERY_METERING);
        assertThat(metering).hasSize(5);
    }
}
