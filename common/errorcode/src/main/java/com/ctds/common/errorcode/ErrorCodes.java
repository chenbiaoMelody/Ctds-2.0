package com.ctds.common.errorcode;

/**
 * 平台公共域（10）+ 通用模块（00）错误码表。各业务模块在自己的常量类中登记码表，禁止裸写字符串。
 */
public final class ErrorCodes {

    public static final ErrorCode PARAM_INVALID = ErrorCode.of("1000C0001");
    public static final ErrorCode UNAUTHORIZED = ErrorCode.of("1000C0002");
    public static final ErrorCode RESOURCE_NOT_FOUND = ErrorCode.of("1000C0003");
    public static final ErrorCode METHOD_NOT_ALLOWED = ErrorCode.of("1000C0004");
    public static final ErrorCode INTERNAL_ERROR = ErrorCode.of("1000S9999");

    private ErrorCodes() {
    }
}
