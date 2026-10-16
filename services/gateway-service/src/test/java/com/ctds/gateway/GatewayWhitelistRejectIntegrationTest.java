package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    /** 白名单指向一个并不匹配的路由 id → 实际匹配的 delivery-catalog-products 不在白名单内。 */
    private static final String NOT_OPEN_ROUTE = "delivery-not-open-probe";

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

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        UPSTREAM.resetCounters();
        JUDGE.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        JUDGE.respondAllowed("S-whitelist", "S-prov-352");
    }

    @Test
    void nonWhitelistedRouteIsRejectedWithNotFoundCode() {
        final JsonNode body = readTree(client.get().uri("/api/v1/catalog/products/12")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid("S-whitelist", java.util.List.of("provider")))
                .header("X-Ctds-Contract-No", "CT-2026-0004")
                .exchange().expectStatus().isNotFound()
                .expectBody().returnResult().getResponseBody());

        assertThat(body.path("code").asText()).as("白名单外默认拒绝（fail-closed）")
                .isEqualTo("1009C0004");
        assertThat(JUDGE.requestCount()).as("白名单外不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).as("白名单外不进入转发").isZero();
    }

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
