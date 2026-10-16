package com.ctds.gateway.trace;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 反应式 traceId 过滤器（hifi §1.5 / Q8-A，order 最前）：入站 {@code X-Trace-Id} 存在且合法则透传、
 * 否则生成；新号写入反应式上下文（{@link TraceIds#CONTEXT_KEY}）与响应头，供未认证 / 限流 / 拒绝
 * 三类网关自建错误封套与计量事件取用（common-auth 的 401 出口固定 "-"，故网关侧自建三类响应体）。
 *
 * <p>本卡不改 {@code common}：反应式 traceId 归一沉淀建议随交付说明提交（沿 DB-26/DB-30
 * 条件触发式口径）。</p>
 */
@Component
public class TraceIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        final String traceId = TraceIds.resolve(exchange.getRequest().getHeaders().getFirst(TraceIds.HEADER));
        exchange.getResponse().getHeaders().set(TraceIds.HEADER, traceId);
        return chain.filter(exchange).contextWrite(context -> context.put(TraceIds.CONTEXT_KEY, traceId));
    }

    @Override
    public int getOrder() {
        return -300;
    }
}
