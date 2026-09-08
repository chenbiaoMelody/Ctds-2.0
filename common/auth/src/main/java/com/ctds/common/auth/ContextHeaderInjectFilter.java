package com.ctds.common.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 信任边界过滤器二（网关侧，位于安全链之后即认证已通过）：把 JWT 声明注入下游请求头
 * （claim sub → subject 头，配置的角色 claim → 逗号分隔角色头），供内部服务 AuthContext 读取。
 * 角色 claim 兼容数组与逗号分隔字符串两种写法；先剥离后注入，保证下游只见到网关权威值。
 */
public class ContextHeaderInjectFilter implements WebFilter, Ordered {

    private final AuthProperties properties;

    public ContextHeaderInjectFilter(final AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> inject(exchange, token.getToken()))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange inject(final ServerWebExchange exchange, final Jwt jwt) {
        final String subject = jwt.getSubject();
        final String rolesValue = String.join(",", resolveRoles(jwt));
        return exchange.mutate()
                .request(request -> request.headers(httpHeaders -> {
                    httpHeaders.remove(properties.getHeader().getSubject());
                    httpHeaders.remove(properties.getHeader().getRoles());
                    if (subject != null && !subject.isBlank()) {
                        httpHeaders.set(properties.getHeader().getSubject(), subject.trim());
                    }
                    if (!rolesValue.isEmpty()) {
                        httpHeaders.set(properties.getHeader().getRoles(), rolesValue);
                    }
                }))
                .build();
    }

    private List<String> resolveRoles(final Jwt jwt) {
        // 读原始 claim（不用 getClaimAsStringList：其会把非字符串元素转写为字符串，
        // 评审②P3-2：数字等无意义声明项应直接跳过，不注入角色头）
        final List<String> roles = new ArrayList<>();
        final Object rawClaim = jwt.getClaim(properties.getJwt().getRolesClaim());
        if (rawClaim instanceof String text) {
            addSegments(roles, text);
        } else if (rawClaim instanceof List<?> entries) {
            for (final Object entry : entries) {
                if (entry instanceof String text) {
                    addSegments(roles, text);
                }
            }
        }
        return roles;
    }

    private void addSegments(final List<String> roles, final String value) {
        for (final String segment : value.split(",")) {
            if (!segment.isBlank()) {
                roles.add(segment.trim());
            }
        }
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
