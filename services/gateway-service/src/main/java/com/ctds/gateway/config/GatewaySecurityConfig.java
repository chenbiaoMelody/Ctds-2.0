package com.ctds.gateway.config;

import com.ctds.common.auth.AuthProperties;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.error.GatewayErrorWriter;
import com.ctds.gateway.security.GatewayUnauthorizedEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;

/**
 * 网关安全装配（hifi §1.2 / §1.3）：开启 {@code ctds.gateway.*} 配置绑定，并提供带追踪号的
 * 401 出口（覆盖 common-auth 的 {@code @ConditionalOnMissingBean} 缺省入口点，见
 * {@link GatewayUnauthorizedEntryPoint} 类注释）。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewaySecurityConfig {

    @Bean
    public ServerAuthenticationEntryPoint gatewayUnauthorizedEntryPoint(
            final ObjectProvider<AuditRecorder> auditRecorder,
            final ObjectProvider<AuthProperties> authProperties,
            final GatewayErrorWriter errorWriter) {
        final AuthProperties properties = authProperties.getIfAvailable();
        final boolean auditEnabled = properties == null || properties.getAudit().isEnabled();
        return new GatewayUnauthorizedEntryPoint(auditRecorder.getIfAvailable(), auditEnabled, errorWriter);
    }
}
