package com.ctds.kms.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * KMS 服务码表（模块位 02，1002 段；沿 ADR-006 §3.5 顺延制度，裁决留痕 ADR-015）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3：不暴露内部实现）。
 */
public final class KmsErrorCodes {

    /** 输入不合法：keyRef 缺失/超长/非法字符、请求体缺失。→ 400 */
    public static final ErrorCode KMS_INPUT_INVALID = ErrorCode.of("1002C0001");
    /** 密钥编号已存在。→ 400 */
    public static final ErrorCode KMS_KEY_ALREADY_EXISTS = ErrorCode.of("1002B0001");
    /** 密钥编号或版本不存在。→ 400 */
    public static final ErrorCode KMS_KEY_NOT_FOUND = ErrorCode.of("1002B0002");
    /** KMS 服务内部错误。→ 500 */
    public static final ErrorCode KMS_INTERNAL_ERROR = ErrorCode.of("1002S0001");

    private KmsErrorCodes() {
    }
}
