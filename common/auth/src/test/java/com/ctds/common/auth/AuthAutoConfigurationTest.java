package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Service-side wiring (review 4 P2-1): the RBAC interceptor is registered by default and only
 * when ctds.auth.enabled=true; identity-context reading (AuthContextFilter) is NOT switch-bound;
 * fail-open mode logs the startup warning bean instead of silently removing enforcement.
 */
class AuthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration.class));

    @Test
    void defaultWiresInterceptorAndContextFilter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AccessControl.class);
            assertThat(context).hasSingleBean(RolePermissionMapper.class);
            assertThat(context).hasBean("authRbacWebConfigurer");
            assertThat(context).hasBean("authContextFilter");
            assertThat(context).doesNotHaveBean("authFailOpenWarner");
        });
    }

    @Test
    void disabledSwitchDropsInterceptorButKeepsContextFilterAndWarns() {
        runner.withPropertyValues("ctds.auth.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("authRbacWebConfigurer");
                    assertThat(context).hasBean("authContextFilter");
                    assertThat(context).hasBean("authFailOpenWarner");
                });
    }

    @Test
    void auditOffKeepsJudgementAndCustomMapperOverride() {
        runner.withPropertyValues("ctds.auth.audit.enabled=false",
                        "ctds.auth.permissions.user=item.read")
                .run(context -> {
                    assertThat(context).hasSingleBean(AccessControl.class);
                    final AuthUser user = new AuthUser("u-1", java.util.Set.of("user"));
                    AuthContext.set(user);
                    try {
                        assertThat(context.getBean(AccessControl.class).hasPermission("item.read")).isTrue();
                    } finally {
                        AuthContext.clear();
                    }
                });
    }
}
