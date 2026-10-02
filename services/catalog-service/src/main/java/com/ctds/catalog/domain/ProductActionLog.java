package com.ctds.catalog.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品操作留痕行（WBS-3.3.5 hifi §3.2；载体 = product_action_log，只插不改）。
 * 四要素 = 谁（operatorSubjectNo）/ 何时（createdAt）/ 对哪个产品（productId）/ 动作（action），
 * 变更明细与强制下架理由以业务可读 summary 承载（≤512，不含敏感原文与数据本体）。
 * 动作码值域随 V4 迁移注释登记（沿 3.2.3 V2"留痕动作码登记"先例）；
 * R8 订阅者可见值域 = 变更类六码（DENIED_* 与 GOVERNANCE_VIEW 不对订阅者暴露，Q5-A）。
 *
 * @param productId         产品 id（data_product.id，逻辑引用不建外键）
 * @param action            动作码（封闭值域见 V4 注释：CREATE/UPDATE/PUBLISH/DELIST/FORCE_DELIST/
 *                          CANCEL、DENIED_ 前缀六变体、GOVERNANCE_VIEW）
 * @param operatorSubjectNo 操作者主体编号（DENIED 时 = 被拒者；治理查看 = 实际登录运营主体，DB-29 Q3-A）
 * @param summary           变更摘要（业务可读文本；DENIED 时为 null）
 * @param createdAt         发生时间
 */
public record ProductActionLog(Long id, long productId, String action, String operatorSubjectNo,
        String summary, LocalDateTime createdAt) {

    /** 动作码常量（成功六类，服务端封闭值域——枚举封闭以 V4 列注释 + T6 探针锚定）。 */
    public static final String ACTION_CREATE = "CREATE";
    /** 动作码常量。 */
    public static final String ACTION_UPDATE = "UPDATE";
    /** 动作码常量。 */
    public static final String ACTION_PUBLISH = "PUBLISH";
    /** 动作码常量。 */
    public static final String ACTION_DELIST = "DELIST";
    /** 动作码常量（summary 恒含强制下架理由全文——行为 4 规则 4）。 */
    public static final String ACTION_FORCE_DELIST = "FORCE_DELIST";
    /** 动作码常量。 */
    public static final String ACTION_CANCEL = "CANCEL";
    /** 动作码常量（治理查看留痕，资源侧/产品侧同码——target 由承载表区分，DB-29 Q2-A 同款）。 */
    public static final String ACTION_GOVERNANCE_VIEW = "GOVERNANCE_VIEW";

    /** 拒绝留痕动作码前缀（DENIED_CREATE/DENIED_UPDATE/DENIED_PUBLISH/DENIED_DELIST/
     * DENIED_FORCE_DELIST/DENIED_CANCEL——沿 dataset DENIED_ 前缀先例）。 */
    public static final String DENIED_PREFIX = "DENIED_";

    /** 构造拒绝留痕动作码（deniedAction("CREATE") → DENIED_CREATE）。 */
    public static String deniedActionOf(final String action) {
        return DENIED_PREFIX + action;
    }

    /**
     * R8 订阅者可见值域（Q5-A：变更类六码，封闭集合——新增动作码默认不可见，须显式纳入；
     * DENIED_* 拒绝留痕与 GOVERNANCE_VIEW 治理查看留痕不对订阅者暴露）。
     */
    public static final List<String> SUBSCRIBER_VISIBLE_ACTIONS = List.of(
            ACTION_CREATE, ACTION_UPDATE, ACTION_PUBLISH, ACTION_DELIST, ACTION_FORCE_DELIST,
            ACTION_CANCEL);
}
