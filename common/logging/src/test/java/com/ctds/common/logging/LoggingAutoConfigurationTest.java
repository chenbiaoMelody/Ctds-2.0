package com.ctds.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 自动装配：默认提供 AuditRecorder Bean；ctds.audit.enabled=false 时收敛（ADR-005 §3 第 6 项）。
 */
class LoggingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

    @Test
    void contextShouldExposeAuditRecorderByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(AuditRecorder.class));
    }

    @Test
    void contextShouldOmitAuditRecorderWhenDisabledButKeepCleanupFilter() {
        runner.withPropertyValues("ctds.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AuditRecorder.class);
                    assertThat(context).hasBean("logContextCleanupFilter");
                });
    }

    @Test
    void contextShouldRegisterCleanupFilterWhenServletPresent() {
        runner.run(context -> assertThat(context).hasBean("logContextCleanupFilter"));
    }
}
