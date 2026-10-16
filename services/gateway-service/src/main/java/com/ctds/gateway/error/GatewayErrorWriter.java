package com.ctds.gateway.error;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCode;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.identity.IdentityHeaderResolver;
import com.ctds.gateway.trace.TraceIds;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * 网关自建错误响应单点（hifi §1.5 / §7）：未认证 / 白名单外 / 凭据非法 / 限流 / 越界透传 /
 * 判定不可达六类响应统一以 {@link ApiResult} 写出（traceId 取 TraceIdWebFilter 写入的反应式上下文，
 * 非 "-"），拒绝路径同时落拒绝留痕（{@link GatewayAuditActions#DELIVERY_DENIED}，独立于计量）。
 *
 * <p>越界场景的 {@code code}/{@code message}/{@code status} 直接来自合约域判定响应的原值
 * （透传，禁止改写）；计量事件不在本类产生。</p>
 */
@Component
public class GatewayErrorWriter {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final String UNKNOWN_ROUTE = "-";

    private final ObjectProvider<AuditRecorder> auditRecorder;
    private final IdentityHeaderResolver identityHeaders;
    private final GatewayProperties properties;

    public GatewayErrorWriter(final ObjectProvider<AuditRecorder> auditRecorder,
            final IdentityHeaderResolver identityHeaders, final GatewayProperties properties) {
        this.auditRecorder = auditRecorder;
        this.identityHeaders = identityHeaders;
        this.properties = properties;
    }

    /** 只写响应（用于未认证出口：审计动作 auth.verify 由调用方落，保持与 common-auth 同一语义）。 */
    public Mono<Void> write(final ServerWebExchange exchange, final HttpStatus status,
            final String code, final String message) {
        return Mono.deferContextual(context -> write(exchange, status, code, message,
                resolveTraceId(context, exchange)));
    }

    /** 写响应 + 记拒绝留痕（交付链拒绝统一入口）。 */
    public Mono<Void> writeDenied(final ServerWebExchange exchange, final HttpStatus status,
            final ErrorCode code, final String message) {
        return Mono.deferContextual(context -> {
            final String traceId = resolveTraceId(context, exchange);
            recordDenied(traceId, exchange, code.value());
            return write(exchange, status, code.value(), message, traceId);
        });
    }

    /** 越界透传：码值/文案/HTTP 均取判定服务原值（禁改写），同时落拒绝留痕。 */
    public Mono<Void> writePassthrough(final ServerWebExchange exchange, final int httpStatus,
            final String code, final String message) {
        return Mono.deferContextual(context -> {
            final String traceId = resolveTraceId(context, exchange);
            recordDenied(traceId, exchange, code);
            return write(exchange, HttpStatus.valueOf(httpStatus), code, message, traceId);
        });
    }

    /**
     * 追踪号解析：反应式上下文（{@link TraceIds#CONTEXT_KEY}，过滤器链内）优先；缺失时回退
     * 响应头的 {@link TraceIds#HEADER} 值（{@code TraceIdWebFilter} 恒先写入）——异常出口
     * （如无路由 advice）位于 WebFilter 链外层订阅位置时上下文不可见，仍可取到真实追踪号。
     */
    private static String resolveTraceId(final ContextView context, final ServerWebExchange exchange) {
        final String fromContext = context.getOrDefault(TraceIds.CONTEXT_KEY, null);
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        return TraceIds.orAbsent(exchange.getResponse().getHeaders().getFirst(TraceIds.HEADER));
    }

    private Mono<Void> write(final ServerWebExchange exchange, final HttpStatus status,
            final String code, final String message, final String traceId) {
        final byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(new ApiResult<Void>(code, message, traceId, null));
        } catch (final Exception serializationFailure) {
            log.error("网关错误封套序列化失败: code={}", code, serializationFailure);
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /** 拒绝留痕：字段最小必要（traceId / 主体 / 合约编号 / 路由 / 结果码），不含请求体与数据原文。 */
    private void recordDenied(final String traceId, final ServerWebExchange exchange, final String code) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("traceId", traceId);
        detail.put("subject", identityHeaders.resolveSubject(exchange));
        detail.put("contractNo", header(exchange, properties.getDelivery().getContractNoHeader()));
        detail.put("routeId", routeId(exchange));
        detail.put("result", code);
        recorder.record(AuditEvent.of(identityHeaders.resolveSubject(exchange),
                GatewayAuditActions.DELIVERY_DENIED, GatewayAuditActions.TARGET_TYPE_DELIVERY,
                header(exchange, properties.getDelivery().getContractNoHeader()), AuditOutcome.DENIED, detail));
    }

    private static String routeId(final ServerWebExchange exchange) {
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? UNKNOWN_ROUTE : route.getId();
    }

    private static String header(final ServerWebExchange exchange, final String name) {
        final String value = exchange.getRequest().getHeaders().getFirst(name);
        return (value == null || value.isBlank()) ? UNKNOWN_ROUTE : value.trim();
    }
}
