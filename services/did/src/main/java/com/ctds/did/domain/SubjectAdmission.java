package com.ctds.did.domain;

/**
 * 主体绑定核验三态（WBS-3.1.9 hifi §5）：不可用与"未入驻"严格分离——不可用不得冒充验证不通过。
 */
public enum SubjectAdmission {
    ADMITTED,
    NOT_ADMITTED,
    UNAVAILABLE
}