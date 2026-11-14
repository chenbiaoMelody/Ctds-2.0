package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.evidence.support.SharedMySqlContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.3 T4（B9-3 fail-closed 双向）：`ctds.domain.schema-list` 与实际数据源 schema
 * 不一致 → 启动失败（不得假装正常）；还原后启动成功（S3-4 演示路径的服务端等价腿）。
 * 手工起停 Spring 上下文（DynamicPropertySource 静态属性无法在同类内构造负向/正向两态）；
 * 数据源经命令行参数注入（优先级高于 profile yml， mysql profile 复位默认 profile 的
 * DataSourceAutoConfiguration 排除项）。
 */
@Testcontainers(disabledWithoutDocker = true)
class DomainConfigStartupFailClosedTest {

    private static final String DB_NEGATIVE = "ctds_evidence_domain_fc_neg";
    private static final String DB_POSITIVE = "ctds_evidence_domain_fc_pos";

    private static MySQLContainer<?> container() {
        return SharedMySqlContainer.container();
    }

    /** 应用骨架：mysql profile 复位默认 profile 的数据源排除项；配置经命令行参数注入。 */
    private static SpringApplication applicationFor() {
        final SpringApplication application = new SpringApplication(EvidenceServiceApplication.class);
        application.setAdditionalProfiles("mysql");
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setLogStartupInfo(false);
        return application;
    }

    private static String[] argsFor(String database, String schemaList) {
        return new String[] {
                "--spring.datasource.url=" + SharedMySqlContainer.jdbcUrlFor(database),
                "--spring.datasource.username=" + container().getUsername(),
                "--spring.datasource.password=" + container().getPassword(),
                "--server.port=0",
                "--ctds.audit.enabled=false",
                "--ctds.auth.rbac.source=jdbc",
                "--ctds.auth.rbac.schema=" + database,
                "--ctds.domain.id=ctds-demo-domain",
                "--ctds.domain.schema-list=" + schemaList};
    }

    @Test
    void mismatchedSchemaListFailsStartup() {
        assertThatThrownBy(() ->
                applicationFor().run(argsFor(DB_NEGATIVE, "ctds_wrong_schema")))
                .as("B9-R4 fail-closed：清单与实际 schema 不一致必须启动失败")
                .hasStackTraceContaining("ctds.domain.schema-list")
                .hasStackTraceContaining(DB_NEGATIVE);
    }

    @Test
    void restoredSchemaListStartsCleanly() {
        try (ConfigurableApplicationContext context =
                applicationFor().run(argsFor(DB_POSITIVE, DB_POSITIVE + ",ctds_subject"))) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().getProperty("ctds.domain.schema-list"))
                    .contains(DB_POSITIVE);
        }
    }
}
