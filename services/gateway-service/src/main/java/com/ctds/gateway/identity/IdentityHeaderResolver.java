package com.ctds.gateway.identity;

import com.ctds.common.auth.AuthProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * 权威身份头读取单点（hifi §8）：头名以 common-auth {@code AuthProperties.header} 为准
 * （可配置），并对其缺省提供兜底常量（{@code ctds.auth.gateway.enabled=false} 时该配置类不装配，
 * 负向用例仍可读取调用主体）。
 *
 * <p>读取的是网关注入后的权威值：入站伪造头已在 order -200 被剥离、order -90 由 JWT 声明重新注入，
 * 故此处取值即"网关按令牌生成的权威值"。</p>
 */
@Component
public class IdentityHeaderResolver {

    /** 兜底头名（沿 ADR-005 §3 第 7 项默认值）。 */
    public static final String DEFAULT_SUBJECT_HEADER = "X-Ctds-Subject";
    public static final String DEFAULT_ROLES_HEADER = "X-Ctds-Roles";

    /** 无主体时的审计占位（沿 AuditEvent 既有口径）。 */
    public static final String ANONYMOUS = "anonymous";

    private final ObjectProvider<AuthProperties> authProperties;

    public IdentityHeaderResolver(final ObjectProvider<AuthProperties> authProperties) {
        this.authProperties = authProperties;
    }

    public String subjectHeaderName() {
        final AuthProperties properties = authProperties.getIfAvailable();
        return properties == null ? DEFAULT_SUBJECT_HEADER : properties.getHeader().getSubject();
    }

    public String rolesHeaderName() {
        final AuthProperties properties = authProperties.getIfAvailable();
        return properties == null ? DEFAULT_ROLES_HEADER : properties.getHeader().getRoles();
    }

    /**
     * 调用主体（缺省 anonymous）：限流 key、审计/计量主体与交付判定请求体 {@code requesterNo} 的取值来源。
     *
     * <p>本值取自 common-auth {@code ContextHeaderInjectFilter}（order -90）按 JWT 声明注入的权威头
     * （入站伪造头已在 order -200 被剥离）——即"网关按令牌生成的主体"，业务代码不另立主体口径
     * （hifi §4.2 / §8-3）。</p>
     */
    public String resolveSubject(final ServerWebExchange exchange) {
        final String value = exchange.getRequest().getHeaders().getFirst(subjectHeaderName());
        return (value == null || value.isBlank()) ? ANONYMOUS : value.trim();
    }
}
