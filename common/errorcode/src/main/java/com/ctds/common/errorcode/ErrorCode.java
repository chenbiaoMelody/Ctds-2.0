package com.ctds.common.errorcode;

import java.util.regex.Pattern;

/**
 * 错误码值对象（ADR-005 §3.3）：平台域(2位数字)+模块(2位数字)+类型(1位 C/B/S)+序号(4位数字)，共 9 位。
 * 成功码 "0" 不使用本对象（见 ApiResult）。各模块码表以常量类登记（参照 ErrorCodes），禁止业务代码裸写字符串。
 */
public record ErrorCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^\\d{4}[CBS]\\d{4}$");

    public ErrorCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid error code: " + value);
        }
    }

    public static ErrorCode of(final String value) {
        return new ErrorCode(value);
    }

    public ErrorType type() {
        return ErrorType.fromChar(value.charAt(4));
    }
}
