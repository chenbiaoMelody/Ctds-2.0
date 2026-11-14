package com.ctds.common.auth;

import com.ctds.common.api.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WBS-3.9.3 Q2-A（网关统一入口，CHG §3"防止绕过"）：退役态代理业务路径 503 + `1013C0004`；
 * 豁免模式与 servlet 侧同一配置键（Q8-A）；缺省 ACTIVE 零行为。
 *
 * <p>order = -150（hifi §9 E-1 勘误位：WebFilter 家族内排序——早于 Spring Security
 * WebFilterChainProxy -100，退役态未认证请求也 503 而非 401；晚于 HeaderStripFilter -200，
 * 与剥离无交互）。traceId 固定 "-"（反应式侧暂无 TraceIdFilter，沿 GatewayAuthEntryPoint 先例）。</p>
 */
public class GatewayDomainStatusWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayDomainStatusWebFilter.class);

    private final DomainProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayDomainStatusWebFilter(final DomainProperties properties) {
        this.properties = properties;
        properties.validateStatus();
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        if (!properties.isRetired() || isExempt(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ApiResult<Void>(
                    DomainErrorCodes.DOMAIN_RETIRED.value(),
                    DomainErrorCodes.DOMAIN_RETIRED_MESSAGE, "-", null));
        } catch (final Exception serializationFailure) {
            log.error("gateway domain retired 503 envelope serialization failed",
                    serializationFailure);
            return Mono.empty();
        }
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private boolean isExempt(final String path) {
        final List<String> patterns = properties.getRetired().getPermitPathPatterns();
        return patterns != null && patterns.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    public int getOrder() {
        return -150;
    }
}
