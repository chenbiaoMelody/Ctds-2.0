package com.ctds.common.auth;

import com.ctds.common.auth.GatewayAuthException.FailReason;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 校验失败原因归一化包装（ADR-005 §3 第 7 项"失败对外一律 401，原因只进审计"）：
 * 把底层 Nimbus/校验器异常统一转成带枚举原因的 {@link GatewayAuthException}，绝不外泄底层报文。
 * 签名失败识别沿异常因果链匹配 Nimbus 的 JWS 校验异常类名（兼容 Spring 对底层异常的包装）。
 */
class MappingJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;

    MappingJwtDecoder(final JwtDecoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Jwt decode(final String token) {
        try {
            return delegate.decode(token);
        } catch (GatewayAuthException ex) {
            throw ex;
        } catch (JwtException ex) {
            throw new GatewayAuthException(classify(ex), ex);
        }
    }

    private FailReason classify(final JwtException ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            final String name = cursor.getClass().getSimpleName();
            if ("BadJWSException".equals(name) || "BadJOSEException".equals(name)) {
                return FailReason.BAD_SIGNATURE;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return FailReason.MALFORMED;
    }
}
