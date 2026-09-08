package com.ctds.common.auth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * 网关侧令牌校验失败的内部原因载体（只进审计 detail.reason 枚举，对外一律统一 401 文案，
 * 不暴露内部实现——ADR-005 §3 第 7 项）。
 */
public class GatewayAuthException extends OAuth2AuthenticationException {

    /** 失败原因枚举（hifi"网关侧"节）。 */
    public enum FailReason {
        MISSING, EXPIRED, BAD_SIGNATURE, BAD_ISSUER, MALFORMED
    }

    private final transient FailReason reason;

    public GatewayAuthException(final FailReason reason) {
        super(new OAuth2Error("invalid_token", "authentication failed", null));
        this.reason = reason;
    }

    public GatewayAuthException(final FailReason reason, final Throwable cause) {
        super(new OAuth2Error("invalid_token", "authentication failed", null), cause);
        this.reason = reason;
    }

    public FailReason getReason() {
        return reason;
    }
}
