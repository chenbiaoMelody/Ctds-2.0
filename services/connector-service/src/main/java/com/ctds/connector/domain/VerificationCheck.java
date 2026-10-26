package com.ctds.connector.domain;

/**
 * 核验三查项（规格行为 7 规则 1）：主体资格 / DID 有效 / 信息完整。
 * 核验拒绝时 failedCheck 取首个不通过项（顺序即枚举声明顺序）。
 */
public enum VerificationCheck {
    SUBJECT_ADMISSION,
    DID_STATUS,
    COMPLETENESS
}
