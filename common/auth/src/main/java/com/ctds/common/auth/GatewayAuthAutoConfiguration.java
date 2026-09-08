package com.ctds.common.auth;

import com.ctds.common.logging.AuditRecorder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;

/**
 * 网关侧 JWT 校验自动装配（组件形态交付，3.5.2 网关服务显式引入 resource-server 依赖并置
 * ctds.auth.gateway.enabled=true 才生效——普通服务 classpath 不含 Spring Security，装配整体不激活）。
 * 职责：校验令牌（B7）、身份头剥离与注入（信任边界）、401 统一出口；服务侧只认上下文头（ADR-005 §3 第 4/7 项）。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({ServerHttpSecurity.class, JwtDecoder.class, ServerAuthenticationEntryPoint.class})
@ConditionalOnProperty(prefix = "ctds.auth.gateway", name = "enabled", havingValue = "true")
public class GatewayAuthAutoConfiguration {

    /**
     * JWT 校验器（B8：secret 与 jwk-set-uri 二选一，同缺/同配启动失败并给出明确原因；
     * SM2 校验器扩展点 = 业务覆盖本 Bean，V1.0 不实现）。
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(final AuthProperties properties) {
        final AuthProperties.Jwt jwtProperties = properties.getJwt();
        final boolean hasSecret = isSet(jwtProperties.getSecret());
        final boolean hasJwkSet = isSet(jwtProperties.getJwkSetUri());
        if (hasSecret == hasJwkSet) {
            throw new IllegalStateException(
                    "ctds.auth.jwt.secret 与 ctds.auth.jwt.jwk-set-uri 必须二选一配置（签名校验密钥来源，ADR-005 §3 第 7 项）");
        }
        final NimbusJwtDecoder delegate;
        if (hasSecret) {
            final byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) {
                throw new IllegalStateException(
                        "ctds.auth.jwt.secret 长度不足 32 字节，不满足 HS256 密钥强度要求（密钥请经配置中心/KMS 下发）");
            }
            delegate = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        } else {
            delegate = NimbusJwtDecoder.withJwkSetUri(jwtProperties.getJwkSetUri()).build();
        }
        delegate.setJwtValidator(new ClaimValidator(Clock.systemUTC(), jwtProperties.getIssuer()));
        return new MappingJwtDecoder(delegate);
    }

    @Bean
    @ConditionalOnMissingBean
    public HeaderStripFilter headerStripFilter(final AuthProperties properties) {
        return new HeaderStripFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextHeaderInjectFilter contextHeaderInjectFilter(final AuthProperties properties) {
        return new ContextHeaderInjectFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ServerAuthenticationEntryPoint.class)
    public ServerAuthenticationEntryPoint gatewayAuthEntryPoint(final AuthProperties properties,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        return new GatewayAuthEntryPoint(auditRecorder.getIfAvailable(), properties.getAudit().isEnabled());
    }

    /**
     * 全路由默认要求认证（B7），白名单 ctds.auth.gateway.permit-paths；未认证走统一 401 出口。
     * 评审①P1-1 修复：开关开启但应用未提供 ServerHttpSecurity（缺 @EnableWebFluxSecurity / 反应式安全自动装配）
     * 时 **启动失败**（fail-fast），绝不静默跳过校验链——静默失效等于鉴权红线旁路。
     */
    @Bean
    public SecurityWebFilterChain gatewayAuthSecurityWebFilterChain(
            final ObjectProvider<ServerHttpSecurity> httpProvider,
            final AuthProperties properties, final JwtDecoder jwtDecoder,
            final ServerAuthenticationEntryPoint gatewayAuthEntryPoint) {
        final ServerHttpSecurity http = httpProvider.getIfAvailable();
        if (http == null) {
            throw new IllegalStateException("已开启 ctds.auth.gateway.enabled，但应用未提供 ServerHttpSecurity："
                    + "网关服务需具备反应式安全自动装配（@EnableWebFluxSecurity 或 Spring Boot 反应式安全自动配置），"
                    + "否则 JWT 校验链无法装配（ADR-005 §3 第 7 项）");
        }
        final List<String> permitPaths = properties.getGateway().getPermitPaths();
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        http.authorizeExchange(exchange -> {
            if (!permitPaths.isEmpty()) {
                exchange.pathMatchers(permitPaths.toArray(new String[0])).permitAll();
            }
            exchange.anyExchange().authenticated();
        });
        http.oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtDecoder(new BlockingToReactiveJwtDecoder(jwtDecoder)))
                .authenticationEntryPoint(gatewayAuthEntryPoint));
        http.exceptionHandling(handling -> handling.authenticationEntryPoint(gatewayAuthEntryPoint));
        return http.build();
    }

    private boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }
}
