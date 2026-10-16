package com.ctds.gateway.audit;

/**
 * 网关审计动作名（hifi §6）：计量事件（放行）与拒绝留痕分列两个动作名，落同一
 * {@code logs/audit-*.jsonl}（common-logging {@code AuditRecorder}），"放行 1 次 ≡ 计量事件 1 条"
 * 的对账锚即按本动作名 + SUCCESS 结果计数（拒绝留痕不与计量混算）。
 */
public final class GatewayAuditActions {

    /** 计量事件（仅放行产生；目标 = 合约编号；detail 不含数据本体与原文）。 */
    public static final String DELIVERY_METERING = "gateway.delivery.metering";

    /** 交付链拒绝留痕（凭据缺失 / 白名单外 / 限流 / 越界 / 发起人不符 / 判定不可达）。 */
    public static final String DELIVERY_DENIED = "gateway.delivery.denied";

    /** 未认证校验失败（沿 common-auth 既有动作名，保持同一审计语义）。 */
    public static final String AUTH_VERIFY = "auth.verify";

    /** 被交付资源类型标识（计量事件 targetType）。 */
    public static final String TARGET_TYPE_DELIVERY = "delivery";

    private GatewayAuditActions() {
    }
}
