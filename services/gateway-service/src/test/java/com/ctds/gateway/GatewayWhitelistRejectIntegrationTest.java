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
 * 交付面白名单"默认拒绝"（hifi §9 T10 边界）：把白名单配置为<b>不包含</b>实际匹配的路由 id，
 * 断言 404 + {@code 1009C0004} 且<b>不调用判定、不触达数据源</b>（fail-closed）。
 *
 * <p><b>DB-47 缺陷 B 扩展</b>：无路由匹配请求（路径不匹配任何路由谓词）与白名单外路径
 * <b>同码同文</b>（404 + {@code 1009C0004}，hifi §7 语义），不调用判定、不转发、不产生计量
 * 事件，拒绝留痕可追溯。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayWhitelistRejectIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    private static final String SUBJECT = "S-whitelist";
    private static final String CONTRACT_NO = "CT-2026-0004";
    private static final String NOT_OPEN_CODE = "1009C0004";

    /** 白名单指向一个并不匹配的路由 id → 实际匹配的 delivery-catalog-products 不在白名单内。 */
    private static final String NOT_OPEN_ROUTE = "delivery-not-open-probe";

    /** 路由内（匹配探针路由）但白名单外。 */
    private static final String IN_ROUTE_PATH = "/api/v1/data-products/12";

    /** 无路由匹配（不匹配任何路由谓词；DB-47 缺陷 B）。 */
    private static final String NO_ROUTE_PATH = "/api/v1/unknown/nothing";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> JUDGE.baseUrl());
        registry.add("ctds.gateway.delivery.routes[0]", () -> NOT_OPEN_ROUTE);
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
        JUDGE.respondAllowed("S-whitelist", "S-prov-352");
        recorder().clear();
    }

    @Test
    void nonWhitelistedRouteIsRejectedWithNotFoundCode() {
        final JsonNode body = delivery(IN_ROUTE_PATH, HttpStatus.NOT_FOUND);

        assertThat(body.path("code").asText()).as("白名单外默认拒绝（fail-closed）")
                .isEqualTo(NOT_OPEN_CODE);
        assertThat(JUDGE.requestCount()).as("白名单外不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).as("白名单外不进入转发").isZero();
    }

    @Test
    void unmatchedPathSharesSameNotFoundEnvelopeAsWhitelistReject() {
        final JsonNode whitelistReject = delivery(IN_ROUTE_PATH, HttpStatus.NOT_FOUND);
        final JsonNode noRoute = delivery(NO_ROUTE_PATH, HttpStatus.NOT_FOUND);

        assertThat(noRoute.path("code").asText()).as("无路由 = 同码（DB-47 缺陷 B）")
                .isEqualTo(NOT_OPEN_CODE);
        assertThat(noRoute.path("message").asText()).as("无路由 = 同文（与白名单外逐字一致）")
                .isEqualTo(whitelistReject.path("message").asText())
                .isNotBlank();
        assertThat(noRoute.path("traceId").asText()).as("无路由封套带真实追踪号")
                .isNotEqualTo("-").isNotBlank();
    }

    @Test
    void unmatchedPathIsRejectedWithoutJudgingForwardingOrMetering() {
        delivery(NO_ROUTE_PATH, HttpStatus.NOT_FOUND);

        assertThat(JUDGE.requestCount()).as("无路由不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).as("无路由不进入转发").isZero();
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_METERING))
                .as("无路由不产生计量事件").isEmpty();
        assertThat(recorder().eventsOf(GatewayAuditActions.DELIVERY_DENIED))
                .as("无路由拒绝留痕 1 条（默认拒绝可追溯）").hasSize(1);
    }

    private JsonNode delivery(final String path, final HttpStatus expected) {
        return readTree(client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(SUBJECT, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isEqualTo(expected.value())
                .expectBody().returnResult().getResponseBody());
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
