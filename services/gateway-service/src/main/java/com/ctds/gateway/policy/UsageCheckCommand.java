package com.ctds.gateway.policy;

/**
 * 判定请求要素（hifi §4.1 请求体）：{@code contractNo} 交付凭据 / {@code requesterNo} 判定主体
 * （来自 JWT {@code sub}，不依赖身份头）/ {@code actionType} 固定 {@code USE}
 * （再分发 REDISTRIBUTE 不在交付调用面）/ {@code purpose} 与 {@code territory} 为策略要素（可空，
 * 由合约域判定"用途不符 / 域外"是否越界）。
 */
public record UsageCheckCommand(String contractNo, String requesterNo, String actionType,
        String purpose, String territory) {

    /** 交付调用面固定动作类型（hifi §5-2 注）。 */
    public static final String ACTION_USE = "USE";
}
