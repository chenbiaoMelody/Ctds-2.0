package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WBS-3.9.3 T10（网关统一入口腿，CHG §3 防绕过）：RETIRED 代理业务路径 503 + `1013C0004`；
 * 豁免路径放行；ACTIVE 缺省零行为；order = -150（hifi §9 E-1 勘误位：早于安全链 -100）。
 */
class GatewayDomainStatusWebFilterTest {

    private static final WebFilterChain MARKER_CHAIN = exchange -> {
        exchange.getAttributes().put("chain.reached", Boolean.TRUE);
        return Mono.empty();
    };

    private static boolean chainReached(ServerWebExchange exchange) {
        return Boolean.TRUE.equals(exchange.getAttributes().get("chain.reached"));
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    @Test
    void activeByDefaultLeavesEveryRequestUntouched() {
        GatewayDomainStatusWebFilter filter = new GatewayDomainStatusWebFilter(new DomainProperties());
        MockServerWebExchange exchange = exchangeFor("/api/v1/subject/records");
        filter.filter(exchange, MARKER_CHAIN).block();
        assertThat(chainReached(exchange)).isTrue();
    }

    @Test
    void retiredBlocksProxiedBusinessPathWith503AndCode() {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        GatewayDomainStatusWebFilter filter = new GatewayDomainStatusWebFilter(properties);
        MockServerWebExchange exchange = exchangeFor("/api/v1/subject/records");
        filter.filter(exchange, MARKER_CHAIN).block();
        assertThat(chainReached(exchange)).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("1013C0004").contains("部署域已退役");
    }

    @Test
    void retiredExemptsAuditReadProxyPath() {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("RETIRED");
        properties.getRetired().getPermitPathPatterns().add("/api/v1/evidence/audit/**");
        GatewayDomainStatusWebFilter filter = new GatewayDomainStatusWebFilter(properties);
        MockServerWebExchange exchange = exchangeFor("/api/v1/evidence/audit/overview");
        filter.filter(exchange, MARKER_CHAIN).block();
        assertThat(chainReached(exchange)).isTrue();
    }

    @Test
    void orderSitsBeforeSecurityChainAndAfterHeaderStrip() {
        GatewayDomainStatusWebFilter filter = new GatewayDomainStatusWebFilter(new DomainProperties());
        assertThat(filter.getOrder()).isEqualTo(-150);
        assertThat(filter.getOrder()).isLessThan(-100);
        assertThat(filter.getOrder()).isGreaterThan(new HeaderStripFilter(new AuthProperties()).getOrder());
    }
}
