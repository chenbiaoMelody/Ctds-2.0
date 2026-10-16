package com.ctds.gateway.delivery;

/**
 * 交付链交换机属性键（过滤器间传参，禁止走请求头——避免与下游信任边界混淆）：
 * 凭据解析写入 {@link #CONTRACT_NO} / {@link #PURPOSE} / {@link #TERRITORY}；策略执行点写入
 * {@link #POLICY_ALLOWED}；计量埋点读全部。
 */
public final class DeliveryAttributes {

    /** 交付凭据（合约编号，已校验格式）。 */
    public static final String CONTRACT_NO = "ctds.gateway.delivery.contractNo";

    /** 本次使用声明的用途（策略要素，可空）。 */
    public static final String PURPOSE = "ctds.gateway.delivery.purpose";

    /** 本次使用声明的域（策略要素，可空）。 */
    public static final String TERRITORY = "ctds.gateway.delivery.territory";

    /** 判定放行标记（仅策略执行点写入；计量埋点据此决定是否产生计量事件）。 */
    public static final String POLICY_ALLOWED = "ctds.gateway.delivery.policyAllowed";

    private DeliveryAttributes() {
    }
}
