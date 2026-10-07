package com.ctds.contract.domain.policy;

/**
 * 策略触发要素枚举（WBS-3.4.5 hifi §2 / §4 五类拦截判定表）：拒绝留痕与 R12 记录承载的
 * 机器可读要素码（枚举名落库，非用户文本——留痕四要素纪律，不含请求原文）。
 *
 * <p>判定语义唯一权威 = {@link PolicyElementCatalog#judgmentSemantics()}（3.4.4 交付的
 * 契约面）——本枚举只承载触发标识，不复制要素语义定义。</p>
 */
public enum PolicyViolation {

    /** 次数耗尽（判定步 9 配额判检一体——超出合约次数上限）。 */
    QUOTA_EXHAUSTED,
    /** 期限届满（判定步 4——当前日期不在 [startDate, endDate] 内，含首末日有效）。 */
    TERM_EXPIRED,
    /** 用途不符（判定步 5——与 purpose.text 精确等值比较失败）。 */
    PURPOSE_MISMATCH,
    /** 域外使用（判定步 6——与 territory.text 精确等值比较失败）。 */
    TERRITORY_MISMATCH,
    /** 再分发动作（判定步 7——noRedistribution 启用时 actionType = REDISTRIBUTE）。 */
    REDISTRIBUTION_FORBIDDEN
}
