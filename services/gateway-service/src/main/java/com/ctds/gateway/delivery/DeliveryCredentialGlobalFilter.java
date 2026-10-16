package com.ctds.gateway.delivery;

import com.ctds.gateway.config.GatewayFilterOrders;
import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.error.GatewayErrorCodes;
import com.ctds.gateway.error.GatewayErrorWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/**
 * 交付凭据解析（hifi §5-2，链路第三道——位于限流之后）：合约编号头缺失 / 格式非法 →
 * 400 + {@code 1009C0001}，<b>不调用判定、不触达数据源</b>（规格行为 1 规则 1：凭合约交付）。
 *
 * <p>格式口径 = 非空且仅含 {@code [A-Za-z0-9._-]}、长度 ≤64（与 common-api traceId 净化同族字符集，
 * 避免把控制字符与空白带入下游与留痕）。</p>
 *
 * <p><b>策略要素编码约定（hifi §5-2，CHG-WBS-3.5.2-hifi-V1.1）</b>：用途 / 域的取值由调用方按
 * <b>URL 百分号编码（UTF-8，RFC 3986）</b>发送，本过滤器解码后交给判定（判定收到的为业务原值，
 * 判定语义零变更）；解码失败（非法百分号序列 / 非法 UTF-8）或解码后长度超上限（≤256 字符）→
 * 400 + {@code 1000C0001}（平台公共段参数码）；<b>空值 = 未声明</b>（口径不变，不解码不落属性）。
 * 单测/联调构造非 ASCII 要素头时必须显式编码（不得依赖 HTTP 客户端自动编码——中文会被编成
 * {@code ?} 造成哑绿，见走查缺陷 C）。</p>
 */
@Component
public class DeliveryCredentialGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** 要素解码后长度上限（hifi §5-2；要素值域为封闭目录短词，上限防超长头注入）。 */
    private static final int ELEMENT_MAX_LENGTH = 256;

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

        final String purpose;
        final String territory;
        try {
            purpose = decodeElement(normalize(exchange.getRequest().getHeaders()
                    .getFirst(properties.getDelivery().getPurposeHeader())));
            territory = decodeElement(normalize(exchange.getRequest().getHeaders()
                    .getFirst(properties.getDelivery().getTerritoryHeader())));
        } catch (final IllegalArgumentException ex) {
            return errorWriter.writeDenied(exchange, HttpStatus.BAD_REQUEST,
                    GatewayErrorCodes.DELIVERY_ELEMENT_INVALID,
                    GatewayErrorCodes.DELIVERY_ELEMENT_INVALID_MESSAGE);
        }
        if (isOverlong(purpose) || isOverlong(territory)) {
            return errorWriter.writeDenied(exchange, HttpStatus.BAD_REQUEST,
                    GatewayErrorCodes.DELIVERY_ELEMENT_INVALID,
                    GatewayErrorCodes.DELIVERY_ELEMENT_INVALID_MESSAGE);
        }
        // 策略要素可空：交换机属性表为 ConcurrentHashMap（不接受 null 值），空值不落属性即"未声明"
        putIfPresent(exchange, DeliveryAttributes.PURPOSE, purpose);
        putIfPresent(exchange, DeliveryAttributes.TERRITORY, territory);
        return chain.filter(exchange);
    }

    /**
     * 要素解码（hifi §5-2：URL 百分号编码，UTF-8）：空值 = 未声明（不解码）；
     * 非法百分号序列 / 非法 UTF-8 → {@link IllegalArgumentException}（由调用处收敛为 400）。
     */
    private static String decodeElement(final String value) {
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isOverlong(final String value) {
        return value != null && value.length() > ELEMENT_MAX_LENGTH;
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
