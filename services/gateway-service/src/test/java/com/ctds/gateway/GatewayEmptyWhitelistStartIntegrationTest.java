package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 交付面白名单"空白名单起点"（3.5.2 补登记 15 / T10 后半）：不配置任何
 * {@code ctds.gateway.delivery.routes}（默认空 = 无交付面可用，hifi §5-1 fail-closed），
 * 路由谓词仍匹配（application.yml 默认探针路由）但白名单为空 → 404 + {@code 1009C0004}，
 * 不调用判定、不触达数据源、不产生计量事件，拒绝留痕可追溯。
 *
 * <p>独立上下文原因：{@link GatewayWhitelistRejectIntegrationTest} 的 DynamicPropertySource
 * 已固定"非空白名单"起点（指向不匹配的路由 id），空白名单起点须零 routes 配置（沿
 * RateLimit / RateLimitDisabled 分类先例）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayEmptyWhitelistStartIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    private static final String SUBJECT = "S-empty-whitelist";
    private static final String CONTRACT_NO = "CT-2026-0005";
    private static final String NOT_OPEN_CODE = "1009C0004";
    private static final String DELIVERY_PATH = "/api/v1/data-products/12";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> JUDGE.baseUrl());
        // 空白名单起点：显式清空 ctds.gateway.delivery.routes（application.yml 默认已开
        // delivery-catalog-products；不显式清空则非"空白名单起点"场景）
        registry.add("ctds.gateway.delivery.routes", () -> "");
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
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        JUDGE.respondAllowed(SUBJECT, "S-prov-352");
        recorder().clear();
    }

    @Test
    void emptyWhitelistStartFailsClosedForMatchedRoute() {
        final JsonNode body = readTree(client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(SUBJECT, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isNotFound()
                .expectBody().returnResult().getResponseBody());

        assertThat(body.path("code").asText()).as("空白名单起点默认拒绝（fail-closed）")
                .isEqualTo(NOT_OPEN_CODE);
        assertThat(JUDGE.requestCount()).as("空白名单不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).as("空白名单不进入转发").isZero();
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_METERING))
                .as("空白名单不产生计量事件").isEmpty();
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_DENIED))
                .as("空白名单拒绝留痕 1 条（可追溯）").hasSize(1);
    }

    private RecordingAuditRecorder recorder() {
        return (RecordingAuditRecorder) auditRecorder;
    }

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
