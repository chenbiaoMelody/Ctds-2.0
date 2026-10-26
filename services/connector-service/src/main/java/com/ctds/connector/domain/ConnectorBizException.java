package com.ctds.connector.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/**
 * 连接器域业务异常（C 型，400；文案为服务端常量）。
 */
public class ConnectorBizException extends BizException {

    public ConnectorBizException(final ErrorCode errorCode, final String message) {
        super(errorCode, message);
    }
}
