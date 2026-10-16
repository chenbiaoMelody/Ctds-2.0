package com.ctds.gateway.config;

/**
 * 网关过滤器顺序契约（hifi §2 交付调用链路顺序，固定不可调）：白名单 → 限流 → 凭据解析 →
 * 策略执行点 → 计量埋点 → 转发。
 *
 * <p>注意：鉴权（身份头剥离 -200 → JWT 校验 → 身份头注入 -90）由 {@code common-auth} 的
 * WebFilter 链承载，其顺序早于本类全部 GlobalFilter（WebFilter 先于网关过滤器链执行）；
 * 本类仅覆盖网关自身的 GlobalFilter 顺序。</p>
 */
public final class GatewayFilterOrders {

    /** 交付面白名单（routeId；白名单外 404 + 1009C0004，不进入后续链路）。 */
    public static final int DELIVERY_WHITELIST = -300;

    /** 限流（主体 + 接口令牌桶；超阈 429 + 1009C0002，不触达上游）。 */
    public static final int RATE_LIMIT = -250;

    /** 凭据解析（合约编号头；缺失/非法 400 + 1009C0001，不调用判定）。 */
    public static final int CREDENTIAL = -200;

    /** 策略执行点（转发前判定；越界原码透传且不触达数据源）。 */
    public static final int POLICY_EXECUTION = -150;

    /** 计量埋点（放行 1 次 ≡ 事件 1 条；拒绝 0 条）。 */
    public static final int METERING = -140;

    private GatewayFilterOrders() {
    }
}
