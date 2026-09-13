package com.ctds.subject.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 主体服务码表（模块位 04，1004 段；1000 平台/1001 国密/1002 幂等锁+KMS/1003 std-adapter 已占用，
 * 占用留痕 ADR-016，沿 ADR-015 §3 惯例）。对外文案为服务端常量，禁止拼接用户输入（章程 4.3）。
 */
public final class SubjectErrorCodes {

    /** 统一社会信用代码已存在且当前状态不可重报。→ 400 */
    public static final ErrorCode SUBJECT_ALREADY_REGISTERED = ErrorCode.of("1004B0001");
    /** 当前状态不可撤销（非待认证或已撤销过）。→ 400 */
    public static final ErrorCode SUBJECT_CANCEL_NOT_ALLOWED = ErrorCode.of("1004C0001");

    private SubjectErrorCodes() {
    }
}
