package com.ctds.gateway.metering;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.config.GatewayRouteConfig;
import com.ctds.gateway.delivery.DeliveryAttributes;
import com.ctds.gateway.identity.IdentityHeaderResolver;
import com.ctds.gateway.trace.TraceIds;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 计量埋点（hifi §6，链路最后一道）：<b>放行 1 次 ≡ 计量事件 1 条</b>；拒绝 0 条（拒绝留痕由
 * {@code GatewayErrorWriter} 以独立动作名承载，不与计量混算）。
 *
 * <p>仅当策略执行点置位 {@link DeliveryAttributes#POLICY_ALLOWED} 时生效，并在转发链完成
 * （响应写出）后落 1 条事件——"放行"= 判定通过且已实际转发。事件字段最小必要
 * （traceId / 主体 / 合约编号 / 被交付资源 / 结果 / 时点），<b>不含请求体、响应体、数据原文、
 * 令牌与密钥</b>（CAT-04 / CAT-07 口径）。</p>
 *
 * <p>出口复用 common-logging {@code AuditRecorder}（异步有界队列，绝不阻塞、绝不抛错）；
 * 计量流水落库、采集与计费归 3.7.5 / 3.7.6，上链归 3.8.3——本卡只保证"事件按口径产生且可对账"。</p>
 */
@Component
public class DeliveryMeteringGlobalFilter implements GlobalFilter, Ordered {

    private static final String UNKNOWN = "-";
    private static final String RESULT_ALLOWED = "ALLOWED";

    private final ObjectProvider<AuditRecorder> auditRecorder;
    private final IdentityHeaderResolver identityHeaders;

    public DeliveryMeteringGlobalFilter(final ObjectProvider<AuditRecorder> auditRecorder,
            final IdentityHeaderResolver identityHeaders) {
        this.auditRecorder = auditRecorder;
        this.identityHeaders = identityHeaders;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!Boolean.TRUE.equals(exchange.getAttribute(DeliveryAttributes.POLICY_ALLOWED))) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange).then(Mono.deferContextual(context -> {
            record(TraceIds.orAbsent(context.getOrDefault(TraceIds.CONTEXT_KEY, null)), exchange);
            return Mono.empty();
        }));
    }

    private void record(final String traceId, final ServerWebExchange exchange) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final String subject = identityHeaders.resolveSubject(exchange);
        final String contractNo = attributeOrUnknown(exchange, DeliveryAttributes.CONTRACT_NO);
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("traceId", traceId);
        detail.put("subject", subject);
        detail.put("contractNo", contractNo);
        detail.put("resource", resource(exchange));
        detail.put("result", RESULT_ALLOWED);
        recorder.record(AuditEvent.of(subject, GatewayAuditActions.DELIVERY_METERING,
                GatewayAuditActions.TARGET_TYPE_DELIVERY, contractNo, AuditOutcome.SUCCESS, detail));
    }

    /** 被交付资源 = routeId + 方法 + 路径模板（不含查询串与原始路径参数）。 */
    private static String resource(final ServerWebExchange exchange) {
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return UNKNOWN;
        }
        final Object template = route.getMetadata().get(GatewayRouteConfig.PATH_TEMPLATE_METADATA);
        return route.getId() + " " + exchange.getRequest().getMethod().name() + " "
                + (template == null ? UNKNOWN : template);
    }

    private static String attributeOrUnknown(final ServerWebExchange exchange, final String name) {
        final String value = exchange.getAttribute(name);
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.METERING;
    }
}
