package com.ctds.gateway.delivery;

import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.config.GatewayRouteConfig;
import com.ctds.gateway.error.GatewayErrorCodes;
import com.ctds.gateway.error.GatewayErrorWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 交付面白名单（hifi §5-1，链路第一道）：{@code ctds.gateway.delivery.routes} 显式列举开放的
 * routeId；<b>默认空 = 无交付面可用（fail-closed）</b>。白名单外 → 404 + {@code 1009C0004}，
 * 不进入限流 / 判定 / 转发（ADR-005 §3 第 2 项"默认拒绝"）。
 *
 * <p>未匹配任何路由的请求不进入网关过滤器链（由框架返回标准 404），本过滤器不介入。</p>
 */
@Component
public class DeliveryWhitelistGlobalFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final GatewayErrorWriter errorWriter;

    public DeliveryWhitelistGlobalFilter(final GatewayProperties properties,
            final GatewayErrorWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        // WBS-3.6.8 W1：fail-closed 仅约束交付面路由（delivery-face 元数据，语义零改动）；
        // 普通代理路由（六门面）与无路由请求不进入本门，鉴权与判定语义归下游服务
        if (route == null || !isDeliveryFace(route)
                || properties.getDelivery().getRoutes().contains(route.getId())) {
            return chain.filter(exchange);
        }
        return errorWriter.writeDenied(exchange, HttpStatus.NOT_FOUND,
                GatewayErrorCodes.DELIVERY_ROUTE_NOT_OPEN,
                GatewayErrorCodes.DELIVERY_ROUTE_NOT_OPEN_MESSAGE);
    }

    /** 交付面标记（配置经路由 metadata 传递；缺省 {@code false} = 普通代理）。 */
    public static boolean isDeliveryFace(final Route route) {
        return Boolean.TRUE.equals(route.getMetadata().get(GatewayRouteConfig.DELIVERY_FACE_METADATA));
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.DELIVERY_WHITELIST;
    }
}
