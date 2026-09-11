package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 条件化集成测试（WBS 2.4.10 B7，沿 2.4.7 "真实中间件在配了它的环境跑"惯例；命名用 *Test 使 surefire 默认拾取）：
 * 仅当环境变量 CTDS_IT_MYSQL_URL 存在时执行，否则跳过——默认门禁环境无 MySQL，保持全绿。
 * 断言：V1/V2 依次应用、flyway_schema_history 两行成功记录、demo_note 恰 2 行、重复迁移 no-op（幂等）。
 * 连接参数：CTDS_IT_MYSQL_URL 必填；CTDS_IT_MYSQL_USER（默认 root）/ CTDS_IT_MYSQL_PASSWORD（默认 ctds-demo）可选。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "CTDS_IT_MYSQL_URL", matches = ".+")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mockMvc;

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
        final String titles = jdbcTemplate.queryForObject(
                "SELECT GROUP_CONCAT(title ORDER BY id SEPARATOR ',') FROM demo_note", String.class);
        assertEquals("迁移演示,版本递进", titles);
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void demoNotesEndpointReturnsMigratedRowsAndRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/demo-notes")
                        .header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[0].title").value("迁移演示"));
        mockMvc.perform(get("/api/v1/demo-notes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }
}
