package com.ctds.common.auth;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WBS-3.9.2 E4：库表映射仅在 ctds.auth.rbac.source=jdbc 且存在 JdbcTemplate 时装配。
 * 早于 AuthAutoConfiguration 注册 Bean，后者 @ConditionalOnMissingBean 自动抑制 → 运行时单一来源；
 * 默认 profile（无数据源）保持配置映射，避免启动失败，且权限查询不回退旧值。
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(JdbcTemplateAutoConfiguration.class)
@AutoConfigureBefore(AuthAutoConfiguration.class)
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(prefix = "ctds.auth.rbac", name = "source", havingValue = "jdbc")
@ConditionalOnBean(JdbcTemplate.class)
public class RbacJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RolePermissionMapper.class)
    public RolePermissionMapper rolePermissionMapper(final JdbcTemplate jdbcTemplate,
            final AuthProperties properties) {
        return new JdbcRolePermissionMapper(jdbcTemplate, properties.getRbac().getSchema());
    }
}
