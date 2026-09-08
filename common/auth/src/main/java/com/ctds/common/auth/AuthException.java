package com.ctds.common.auth;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;
import com.ctds.common.errorcode.ErrorCodes;

/**
 * 鉴权异常：BizException 的鉴权语义子类（错误码仍为平台码表 1000C0002/1000C0005）。
 * 评审①P3-1/P3-2 澄清：只允许携带两个鉴权码（构造即校验，误用在开发期暴露）；
 * 由 AuthAdvice 精确映射 HTTP 401/403（GlobalExceptionHandler 对 BizException 的默认映射不变）。
 */
public class AuthException extends BizException {

    public AuthException(final ErrorCode errorCode, final String message) {
        super(assertAuthCode(errorCode), message);
    }

    private static ErrorCode assertAuthCode(final ErrorCode errorCode) {
        if (!ErrorCodes.UNAUTHORIZED.equals(errorCode) && !ErrorCodes.FORBIDDEN.equals(errorCode)) {
            throw new IllegalArgumentException(
                    "AuthException only carries auth codes 1000C0002/1000C0005, got: " + errorCode);
        }
        return errorCode;
    }
}
