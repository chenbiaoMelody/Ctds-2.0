package com.ctds.gateway.security;

import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.GatewayAuthException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.error.GatewayErrorWriter;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关未认证统一出口（hifi §1.5 / Q8-A）：401 + {@code 1000C0002} + 服务端常量文案，
 * traceId 取反应式上下文（非 common-auth 的固定 "-"）；失败原因只进审计
 * （{@link GatewayAuditActions#AUTH_VERIFY} FAILURE，detail 仅含 reason 枚举，禁含令牌原文）。
 *
 * <p>存在理由：common-auth 的 {@code GatewayAuthEntryPoint} 固定 traceId "-"（其类注释为已知限制），
 * 而本卡 Q8-A 要求未认证响应亦带追踪号；common-auth 一行未改——本类以"网关侧自建响应体"方式满足
 * 契约（common-auth 的入口点 Bean 为 {@code @ConditionalOnMissingBean}，本 Bean 优先）。</p>
 */
public class GatewayUnauthorizedEntryPoint implements ServerAuthenticationEntryPoint {

    private final AuditRecorder auditRecorder;
    private final boolean auditEnabled;
    private final GatewayErrorWriter errorWriter;

    public GatewayUnauthorizedEntryPoint(final AuditRecorder auditRecorder, final boolean auditEnabled,
            final GatewayErrorWriter errorWriter) {
        this.auditRecorder = auditRecorder;
        this.auditEnabled = auditEnabled;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> commence(final ServerWebExchange exchange, final AuthenticationException ex) {
        recordFailure(resolveReason(ex));
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED.value(),
                AuthAdvice.UNAUTHORIZED_MESSAGE);
    }

    /** 失败原因口径与 common-auth 逐字一致（MALFORMED 兜底于 OAuth2 异常、MISSING 兜底于其它）。 */
    private GatewayAuthException.FailReason resolveReason(final AuthenticationException ex) {
        if (ex instanceof GatewayAuthException gatewayEx) {
            return gatewayEx.getReason();
        }
        if (ex instanceof OAuth2AuthenticationException oauthEx
                && oauthEx.getCause() instanceof GatewayAuthException gatewayEx) {
            return gatewayEx.getReason();
        }
        if (ex instanceof OAuth2AuthenticationException) {
            return GatewayAuthException.FailReason.MALFORMED;
        }
        return GatewayAuthException.FailReason.MISSING;
    }

    private void recordFailure(final GatewayAuthException.FailReason reason) {
        if (!auditEnabled || auditRecorder == null) {
            return;
        }
        auditRecorder.record(AuditEvent.of("anonymous", GatewayAuditActions.AUTH_VERIFY, "jwt", null,
                AuditOutcome.FAILURE, Map.of("reason", reason.name())));
    }
}
