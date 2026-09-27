package com.ctds.space.domain;

/**
 * 主体入驻资格三态（WBS-3.2.3 hifi §4；单一事实源在 subject-service，ADR-016 §6 衔接契约）。
 * 不可用与"未入驻"严格分离——不可用不得冒充资格拒绝（防枚举同形口径，沿 did SubjectAdmission 先例）。
 */
public enum SubjectAdmission {

    /** 已入驻：放行。 */
    ADMITTED,
    /** 未入驻或主体不存在：统一文案拒绝（1006C0001，不区分两者——防枚举）。 */
    NOT_ADMITTED,
    /** 主体服务不可达/失败/响应异常：如实"不可用"（1006S0001，不冒充资格拒绝）。 */
    UNAVAILABLE
}
