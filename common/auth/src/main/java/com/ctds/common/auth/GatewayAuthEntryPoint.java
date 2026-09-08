package com.ctds.common.auth;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.GatewayAuthException.FailReason;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关侧未认证统一出口：一律 401 + 统一封套（码 1000C0002，服务端常量文案，不暴露内部原因），
 * 内部原因仅写审计 auth.verify FAILURE（detail 只含 reason 枚举，禁含令牌原文）；
 * 响应式侧暂无 TraceIdFilter，traceId 固定 "-"（hifi 已知限制）。审计器缺省/关闭时只出响应。
 */
public class GatewayAuthEntryPoint implements ServerAuthenticationEntryPoint {

    static final String AUDIT_ACTION_AUTH_VERIFY = "auth.verify";

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthEntryPoint.class);

    private final AuditRecorder auditRecorder;
    private final boolean auditEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayAuthEntryPoint(final AuditRecorder auditRecorder, final boolean auditEnabled) {
        this.auditRecorder = auditRecorder;
        this.auditEnabled = auditEnabled;
    }

    @Override
    public Mono<Void> commence(final ServerWebExchange exchange, final AuthenticationException ex) {
        final FailReason reason = resolveReason(ex);
        recordFailure(reason);
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ApiResult<Void>(
                    ErrorCodes.UNAUTHORIZED.value(), AuthAdvice.UNAUTHORIZED_MESSAGE, "-", null));
        } catch (final Exception serializationFailure) {
            log.error("auth 401 envelope serialization failed", serializationFailure);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private FailReason resolveReason(final AuthenticationException ex) {
        if (ex instanceof GatewayAuthException gatewayEx) {
            return gatewayEx.getReason();
        }
        if (ex instanceof OAuth2AuthenticationException oauthEx) {
            if (oauthEx.getCause() instanceof GatewayAuthException gatewayEx) {
                return gatewayEx.getReason();
            }
            return FailReason.MALFORMED;
        }
        return FailReason.MISSING;
    }

    private void recordFailure(final FailReason reason) {
        if (!auditEnabled || auditRecorder == null) {
            return;
        }
        auditRecorder.record(AuditEvent.of("anonymous", AUDIT_ACTION_AUTH_VERIFY,
                "jwt", null, AuditOutcome.FAILURE, Map.of("reason", reason.name())));
    }
}
