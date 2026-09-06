package com.ctds.common.errorcode;

/**
 * 业务异常：携带错误码，由 GlobalExceptionHandler 统一转换为响应封套。
 * C/B 型异常的 message 面向调用方展示，**必须为服务端常量文案，禁止拼接用户输入**（防响应回显）；
 * S 型异常的 message 不出站（防止内部实现泄露）。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(final ErrorCode errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
