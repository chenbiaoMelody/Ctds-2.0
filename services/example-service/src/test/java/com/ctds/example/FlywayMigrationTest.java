package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 容器化集成测试（WBS 2.4.11，写法规范见 ADR-010；升级 2.4.10 的环境变量门控为容器自动供给）：
 * 本机 Docker 运行时自动起一次性 MySQL 8 容器（随机端口/随机凭据，测后自动销毁）执行，
 * 未运行时 disabledWithoutDocker 自动跳过——门禁不红（跳过态留痕于 mvn 输出）。
 * 断言集与 2.4.10 定稿一致：V1/V2 依次应用、flyway_schema_history 两行成功记录、demo_note 恰 2 行、
 * 数据标题契约、重复迁移 no-op（幂等）、demo-notes 端点 200/401 双向。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    /** 显式镜像标签 mysql:8.0（与 2.4.10 演练镜像同源；ADR-010 禁止 latest），库名与示例迁移目标一致 */
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_demo");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mockMvc;

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
