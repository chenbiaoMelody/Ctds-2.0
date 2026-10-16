package com.ctds.gateway.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 无路由出口（DB-47 缺陷 B，hifi §7）：请求路径不匹配 {@code ctds.gateway.routes} 任何路由时，
 * 网关链与白名单均不介入（无 Route 属性），由 WebFlux 以响应式
 * {@link NoResourceFoundException} 收尾——本 advice 将其收敛为 <b>404 + {@code 1009C0004}</b>
 * （与"路由内但白名单外"经 {@link GatewayErrorWriter} 的出口<b>同码同文同写出口</b>），
 * 拒绝路径同落 {@code delivery.denied} 留痕（默认拒绝可追溯）。
 *
 * <p><b>为何用 advice 接管</b>：{@code common-errorcode} 的 {@code GlobalExceptionHandler} 只识别
 * servlet 版 {@code NoResourceFoundException}，对网关（Reactive）的响应式异常类型会落到其
 * {@code Exception} 兜底（实测 500 + {@code 1000S9999}）；本类以<b>最高优先级 + 精确类型匹配</b>
 * 先于该兜底接管（advice 解析为"先到先得"，顺序由 {@code @Order} 决定，必须最高优先）。
 * <b>不改 common</b>（MVC 组件零触碰）。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayNoRouteAdvice {

    private final GatewayErrorWriter errorWriter;

    public GatewayNoRouteAdvice(final GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Mono<Void> onNoRoute(final ServerWebExchange exchange) {
        return errorWriter.writeDenied(exchange, HttpStatus.NOT_FOUND,
                GatewayErrorCodes.DELIVERY_ROUTE_NOT_OPEN,
                GatewayErrorCodes.DELIVERY_ROUTE_NOT_OPEN_MESSAGE);
    }
}
