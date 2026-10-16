package com.ctds.gateway.delivery;

import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.error.GatewayErrorCodes;
import com.ctds.gateway.error.GatewayErrorWriter;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 交付凭据解析（hifi §5-2，链路第三道——位于限流之后）：合约编号头缺失 / 格式非法 →
 * 400 + {@code 1009C0001}，<b>不调用判定、不触达数据源</b>（规格行为 1 规则 1：凭合约交付）。
 *
 * <p>格式口径 = 非空且仅含 {@code [A-Za-z0-9._-]}、长度 ≤64（与 common-api traceId 净化同族字符集，
 * 避免把控制字符与空白带入下游与留痕）；用途 / 域为策略要素（可空），原样交给判定，由合约域
 * 判定"用途不符 / 域外"是否越界（网关不做第二套语义）。</p>
 */
@Component
public class DeliveryCredentialGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final GatewayProperties properties;
    private final GatewayErrorWriter errorWriter;

    public DeliveryCredentialGlobalFilter(final GatewayProperties properties,
            final GatewayErrorWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String contractNo = normalize(exchange.getRequest().getHeaders()
                .getFirst(properties.getDelivery().getContractNoHeader()));
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            return errorWriter.writeDenied(exchange, HttpStatus.BAD_REQUEST,
                    GatewayErrorCodes.DELIVERY_CREDENTIAL_INVALID,
                    GatewayErrorCodes.DELIVERY_CREDENTIAL_INVALID_MESSAGE);
        }
        exchange.getAttributes().put(DeliveryAttributes.CONTRACT_NO, contractNo);
        // 策略要素可空：交换机属性表为 ConcurrentHashMap（不接受 null 值），空值不落属性即"未声明"
        putIfPresent(exchange, DeliveryAttributes.PURPOSE, normalize(exchange.getRequest().getHeaders()
                .getFirst(properties.getDelivery().getPurposeHeader())));
        putIfPresent(exchange, DeliveryAttributes.TERRITORY, normalize(exchange.getRequest().getHeaders()
                .getFirst(properties.getDelivery().getTerritoryHeader())));
        return chain.filter(exchange);
    }

    private static void putIfPresent(final ServerWebExchange exchange, final String name,
            final String value) {
        if (value != null) {
            exchange.getAttributes().put(name, value);
        }
    }

    private static String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrders.CREDENTIAL;
    }
}
