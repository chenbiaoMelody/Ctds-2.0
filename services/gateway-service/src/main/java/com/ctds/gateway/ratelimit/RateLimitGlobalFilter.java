package com.ctds.gateway.ratelimit;

import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.config.GatewayRouteConfig;
import com.ctds.gateway.error.GatewayErrorCodes;
import com.ctds.gateway.error.GatewayErrorWriter;
import com.ctds.gateway.identity.IdentityHeaderResolver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 限流（hifi §3，链路第二道）：维度 = <b>调用主体 + 接口</b>，key = {@code 主体#routeId#方法 路径模板}
 * （用路径模板而非原始路径，避免路径参数造成 key 爆炸）；算法 = 进程内令牌桶，容量与补充速率可配置。
 *
 * <p>超阈 → 429 + {@code 1009C0002}（附 Retry-After），<b>不触达上游、不产生计量事件、不影响业务配额
 * 计数</b>——限流是网络级频率保护，业务次数配额唯一由合约域策略执行引擎判定与计数
 * （规格行为 2 规则 5）。未认证请求已在安全链被 401 拦下，不进入本过滤器。</p>
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final String KEY_SEPARATOR = "#";
    private static final String UNKNOWN_TEMPLATE = "-";

    private final GatewayProperties properties;
    private final GatewayErrorWriter errorWriter;
    private final IdentityHeaderResolver identityHeaders;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitGlobalFilter(final GatewayProperties properties,
            final GatewayErrorWriter errorWriter, final IdentityHeaderResolver identityHeaders) {
        this(properties, errorWriter, identityHeaders, System::nanoTime);
    }

    /** 测试用构造：注入可控纳秒时钟（令牌桶补充速率的时间来源）。 */
    RateLimitGlobalFilter(final GatewayProperties properties, final GatewayErrorWriter errorWriter,
            final IdentityHeaderResolver identityHeaders, final LongSupplier nanoClock) {
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.identityHeaders = identityHeaders;
        this.nanoClock = nanoClock;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!properties.getRateLimit().isEnabled()) {
            return chain.filter(exchange);
        }
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        final TokenBucket bucket = buckets.computeIfAbsent(keyOf(exchange, route),
                ignored -> newBucket(route.getId()));
        if (bucket.tryAcquire(nanoClock.getAsLong())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                Long.toString(bucket.retryAfterSeconds()));
        return errorWriter.writeDenied(exchange, HttpStatus.TOO_MANY_REQUESTS,
                GatewayErrorCodes.RATE_LIMITED, GatewayErrorCodes.RATE_LIMITED_MESSAGE);
    }

    private TokenBucket newBucket(final String routeId) {
        final GatewayProperties.RateLimit.Bucket override =
                properties.getRateLimit().getRoutes().get(routeId);
        final int capacity = override != null && override.getCapacity() != null
                ? override.getCapacity() : properties.getRateLimit().getDefaultCapacity();
        final int refillPerSecond = override != null && override.getRefillPerSecond() != null
                ? override.getRefillPerSecond() : properties.getRateLimit().getDefaultRefillPerSecond();
        return new TokenBucket(Math.max(1, capacity), Math.max(1, refillPerSecond), nanoClock.getAsLong());
    }

    private String keyOf(final ServerWebExchange exchange, final Route route) {
        return identityHeaders.resolveSubject(exchange) + KEY_SEPARATOR + route.getId() + KEY_SEPARATOR
                + exchange.getRequest().getMethod().name() + ' ' + pathTemplate(route);
    }

    /** 路径模板来自路由 metadata（GatewayRouteConfig 写入；缺省 "-" 不阻塞判定）。 */
    private static String pathTemplate(final Route route) {
        final Object value = route.getMetadata().get(GatewayRouteConfig.PATH_TEMPLATE_METADATA);
        return value == null ? UNKNOWN_TEMPLATE : value.toString();
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.RATE_LIMIT;
    }
}
