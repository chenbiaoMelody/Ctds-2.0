package com.ctds.connector.domain;

/**
 * 主体入驻资格三态（沿 catalog SubjectAdmission 先例）：
 * UNAVAILABLE = 不可判定（不可达/超时/非 200/不可解析）——**不冒充未入驻**，调用方按 fail-closed 返回 503。
 */
public enum SubjectAdmission {
    ADMITTED,
    NOT_ADMITTED,
    UNAVAILABLE
}
