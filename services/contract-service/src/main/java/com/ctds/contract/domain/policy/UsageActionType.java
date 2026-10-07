package com.ctds.contract.domain.policy;

/**
 * 使用动作类型（WBS-3.4.5 hifi §2）：USE（使用）/ REDISTRIBUTE（再分发——转授/转售/对外
 * 提供的统一抽象，平台内动作语义；具体动作面归 C-5.x 映射，ADR-020 登记）。
 */
public enum UsageActionType {

    /** 使用（默认动作语义）。 */
    USE,
    /** 再分发（转授/转售/对外提供——usage.no_redistribution 启用时拦截，判定步 7）。 */
    REDISTRIBUTE
}
