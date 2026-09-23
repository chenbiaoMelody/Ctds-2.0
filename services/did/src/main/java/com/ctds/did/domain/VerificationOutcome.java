package com.ctds.did.domain;

/**
 * 验证结论（WBS-3.1.9 hifi §1：PASS/FAIL/UNAVAILABLE 三值——UNAVAILABLE = 核验组件不可用，
 * 系统态与身份结论分离；验证结论非 DID 状态枚举，ADR-017 §2.9 不冲突）。
 */
public enum VerificationOutcome {
    PASS,
    FAIL,
    UNAVAILABLE
}