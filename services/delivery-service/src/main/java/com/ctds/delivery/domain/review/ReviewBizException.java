package com.ctds.delivery.domain.review;

import com.ctds.common.errorcode.ErrorCode;
import com.ctds.delivery.domain.DeliveryBizException;

/**
 * 评价域业务异常（沿 SandboxBizException 先例）：继承交付域异常，
 * 复用统一出站映射（DeliveryExceptionHandler MAPPED_CODES 登记制）；文案一律服务端常量。
 */
public class ReviewBizException extends DeliveryBizException {

    public ReviewBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }

    public ReviewBizException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode, message, cause);
    }
}
