package com.ctds.delivery.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 沙箱业务异常（hifi §1.1）：继承交付域异常，复用 1009 段统一出站映射；
 * 文案一律服务端常量（不含内部细节）。
 */
public class SandboxBizException extends DeliveryBizException {

    public SandboxBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }

    public SandboxBizException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode, message, cause);
    }
}
