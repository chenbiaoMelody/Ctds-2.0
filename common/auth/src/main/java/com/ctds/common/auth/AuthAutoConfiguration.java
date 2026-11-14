package com.ctds.common.auth;

import com.ctds.common.logging.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 服务侧自动装配（servlet Web 环境，注册模式沿 errorcode/logging）：
 * 身份上下文过滤器 + RBAC 判定内核 + 注解强制拦截器 + 鉴权错误映射
 * + 部署域状态过滤器与生命周期审计发射器（WBS-3.9.3 Q2-A/Q5-A）。
 * 上下文读取不设开关（B1）；ctds.auth.enabled=false 仅撤下 RBAC 拦截器（hifi"注解式强制"节）。
 */
@AutoConfiguration
@EnableConfigurationProperties({AuthProperties.class, DomainProperties.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthAutoConfiguration.class);

    /** 紧随 TraceIdFilter（HIGHEST_PRECEDENCE）之后，使鉴权日志携带 traceId。 */
    @Bean
    public FilterRegistrationBean<AuthContextFilter> authContextFilter(final AuthProperties properties) {
        final FilterRegistrationBean<AuthContextFilter> registration =
                new FilterRegistrationBean<>(new AuthContextFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * WBS-3.9.3 Q2-A（servlet 侧）：部署域退役态拦截（HIGHEST_PRECEDENCE+30，紧随身份上下文
     * 过滤器）；缺省 ACTIVE 零行为；不挂 ctds.auth.enabled 条件（hifi §9 E-3 防绕过口径）。
     */
    @Bean
    public FilterRegistrationBean<DomainStatusFilter> domainStatusFilter(
            final DomainProperties domainProperties) {
        final FilterRegistrationBean<DomainStatusFilter> registration =
                new FilterRegistrationBean<>(new DomainStatusFilter(domainProperties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }

    /** WBS-3.9.3 Q5-A：生命周期三键齐备时启动落 `domain.lifecycle` 审计事件（缺省零事件）。 */
    @Bean
    public DomainLifecycleAuditEmitter domainLifecycleAuditEmitter(
            final DomainProperties domainProperties,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        return new DomainLifecycleAuditEmitter(domainProperties, auditRecorder.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public RolePermissionMapper rolePermissionMapper(final AuthProperties properties) {
        return new ConfigRolePermissionMapper(properties.getPermissions());
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessControl accessControl(final RolePermissionMapper mapper,
            final ObjectProvider<AuditRecorder> auditRecorder, final AuthProperties properties) {
        return new DefaultAccessControl(mapper, auditRecorder.getIfAvailable(), properties.getAudit().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthAdvice authAdvice() {
        return new AuthAdvice();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ctds.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebMvcConfigurer authRbacWebConfigurer(final AccessControl accessControl) {
        final PermissionInterceptor interceptor = new PermissionInterceptor(accessControl);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(final InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    /**
     * 评审②P2-1：ctds.auth.enabled=false 时启动 WARN——@RequirePermission 端点将 fail-open
     * （静默公开），提醒部署前提：服务端口必须网络隔离仅网关可达，生产禁止关闭本开关。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ctds.auth", name = "enabled", havingValue = "false")
    public InitializingBean authFailOpenWarner() {
        return () -> log.warn("ctds.auth.enabled=false: RBAC enforcement is OFF — all @RequirePermission "
                + "endpoints are unprotected. Keep the service port network-isolated (gateway-only) "
                + "and never use this switch in production (ADR-005 §3 item 7)");
    }
}
