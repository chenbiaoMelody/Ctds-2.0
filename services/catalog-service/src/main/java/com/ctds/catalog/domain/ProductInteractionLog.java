package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 目录域收藏订阅动作留痕行（WBS-3.3.4 hifi §3；载体 = product_interaction_log，只插不改）。
 * 四要素 = 谁（subjectNo）/ 何时（createdAt）/ 对哪个产品（productId）/ 动作 + 结果（action + outcome）；
 * 不含敏感原文（行为 6 规则 4 / 行为 7 规则 4 口径）。
 *
 * @param subjectNo  操作者主体编号（DENIED 时 = 被拒者）
 * @param productId  产品 id（请求目标）
 * @param action     动作码：FAVORITE / UNFAVORITE / SUBSCRIBE / UNSUBSCRIBE（枚举封闭）
 * @param outcome    结果：SUCCEEDED / DENIED（拒绝同样留痕）
 * @param denyReason 拒绝理由码（DENIED 时落错误码尾号，如 C0011；成功为 null）
 * @param createdAt  发生时间
 */
public record ProductInteractionLog(String subjectNo, long productId, String action, String outcome,
        String denyReason, LocalDateTime createdAt) {

    /** 动作码常量（服务端封闭值域，枚举封闭以列注释 + T1 探针锚定）。 */
    public static final String ACTION_FAVORITE = "FAVORITE";
    /** 动作码常量。 */
    public static final String ACTION_UNFAVORITE = "UNFAVORITE";
    /** 动作码常量。 */
    public static final String ACTION_SUBSCRIBE = "SUBSCRIBE";
    /** 动作码常量。 */
    public static final String ACTION_UNSUBSCRIBE = "UNSUBSCRIBE";
    /** 结果常量：成功。 */
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    /** 结果常量：拒绝。 */
    public static final String OUTCOME_DENIED = "DENIED";
}
