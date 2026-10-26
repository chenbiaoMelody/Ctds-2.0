package com.ctds.connector.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 跨域依赖不可用（S 型码，需精确映射 503 并保留业务文案——全局 S 型处理器会脱敏为"系统繁忙"）。
 * fail-closed：系统不可用绝不被解释为业务结论（不冒充"未入驻 / DID 无效"）。
 */
public class ConnectorUnavailableException extends RuntimeException {

    private final ErrorCode errorCode;

    public ConnectorUnavailableException(final ErrorCode errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
