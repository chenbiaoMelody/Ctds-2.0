package com.ctds.delivery.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/** 交付域业务异常（由 interfaces.DeliveryExceptionHandler 精确映射 400/403/404/422/503/500）。 */
public class DeliveryBizException extends BizException {

    public DeliveryBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }

    public DeliveryBizException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode, message, cause);
    }
}
