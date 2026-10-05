package com.ctds.contract.domain;

/**
 * 主体资格三态（沿 catalog SubjectAdmission 语义；单一事实源在主体服务，ADR-016 §6）。
 * UNAVAILABLE = 主体服务不可达/失败（不冒充"未入驻"——防枚举口径，hifi §3 1008S0001）。
 */
public enum SubjectAdmission {

    /** 已入驻（ADMITTED）。 */
    ADMITTED,
    /** 未入驻或主体不存在（资格不成立——统一文案防枚举，不区分两态）。 */
    NOT_ADMITTED,
    /** 主体资格服务不可用（fail-closed，不冒充资格成立/不成立）。 */
    UNAVAILABLE
}
