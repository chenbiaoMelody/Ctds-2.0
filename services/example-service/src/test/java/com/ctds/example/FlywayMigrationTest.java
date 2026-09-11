package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 条件化集成测试（WBS 2.4.10 B7，沿 2.4.7 "真实中间件在配了它的环境跑"惯例；命名用 *Test 使 surefire 默认拾取）：
 * 仅当环境变量 CTDS_IT_MYSQL_URL 存在时执行，否则跳过——默认门禁环境无 MySQL，保持全绿。
 * 断言：V1/V2 依次应用、flyway_schema_history 两行成功记录、demo_note 恰 2 行、重复迁移 no-op（幂等）。
 * 连接参数：CTDS_IT_MYSQL_URL 必填；CTDS_IT_MYSQL_USER（默认 root）/ CTDS_IT_MYSQL_PASSWORD（默认 ctds-demo）可选。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "CTDS_IT_MYSQL_URL", matches = ".+")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CTDS_IT_MYSQL_URL"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("CTDS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CTDS_IT_MYSQL_PASSWORD", "ctds-demo"));
    }

    @Test
    void migrationsAppliedInOrderAndNoopOnRerun() {
        final Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = 1",
                Integer.class);
        assertEquals(2, applied);
        final Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_note", Integer.class);
        assertEquals(2, rows);
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
