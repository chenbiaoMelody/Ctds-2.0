package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 存证概览路由透传（WBS-3.8.6 hifi §1 T9；WBS-3.8.2 connector-evidence 续延）：
 * {@code GET /api/v1/evidence/overview} 经网关普通代理转发到 evidence 上游，**响应体逐字透传**
 * （含中文不丢编码），且不进交付判定链、零交付计量事件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayEvidenceRouteTransparencyIntegrationTest {

    private static final StubServer EVIDENCE = new StubServer();
    private static final StubServer JUDGE = new StubServer();
    private static final String OVERVIEW_BODY =
            "{\"code\":\"0\",\"message\":\"成功\",\"data\":{\"onChainCount\":9,\"pendingCount\":0,"
                    + "\"failedCount\":1,\"lastFailedAt\":\"2026-11-05T13:30:00.000Z\"}}";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_EVIDENCE_BASEURL", () -> EVIDENCE.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> JUDGE.baseUrl());
    }

    @AfterAll
    static void stopStubs() {
        EVIDENCE.close();
        JUDGE.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingAuditRecorder recorder;

    /** T9：概览经网关转发成功 + 响应体逐字透传 + 判定链零调用 + 零计量。 */
    @Test
    void evidenceOverviewForwardsAndPassesResponseBodyThrough() {
        EVIDENCE.resetCounters();
        EVIDENCE.respondUpstream(200, OVERVIEW_BODY);
        JUDGE.resetCounters();
        JUDGE.respondAllowed("S-prov-386", "S-prov-386");
        recorder.clear();

        final String body = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port).build()
                .get().uri("/api/v1/evidence/overview")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid("S-admin-evidence", List.of("admin")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(EVIDENCE.lastPath()).as("上游实收路径 = 概览端点")
                .isEqualTo("/api/v1/evidence/overview");
        assertThat(body).as("响应体逐字透传（中文不丢编码）").isEqualTo(OVERVIEW_BODY);
        assertThat(JUDGE.requestCount()).as("存证门面不进交付判定链").isZero();
        assertThat(recorder.eventsOf(GatewayAuditActions.DELIVERY_METERING))
                .as("存证门面零交付计量事件").isEmpty();
    }
}
