package com.ctds.common.auth;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 信任边界过滤器一（网关侧，先于安全链）：无条件剥离入站的上下文头——服务只认网关注入的身份（ADR-005 §3 第 7 项），
 * 防止外部伪造 X-Ctds-Subject/X-Ctds-Roles 冒领身份。
 */
public class HeaderStripFilter implements WebFilter, Ordered {

    private final AuthProperties.Header headers;

    public HeaderStripFilter(final AuthProperties properties) {
        this.headers = properties.getHeader();
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        final ServerWebExchange stripped = exchange.mutate()
                .request(request -> request.headers(httpHeaders -> {
                    httpHeaders.remove(headers.getSubject());
                    httpHeaders.remove(headers.getRoles());
                }))
                .build();
        return chain.filter(stripped);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
