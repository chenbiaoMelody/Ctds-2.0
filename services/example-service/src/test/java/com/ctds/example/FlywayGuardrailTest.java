package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.example.support.SharedMySqlContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 迁移护栏容器化测试（WBS 2.4.11 B4，承接 2.4.10 人工演练场景升级为自动回归；规范 ADR-009 规则 5/7 + ADR-010）：
 * 纯 JUnit + Testcontainers + Flyway API（ADR-009 §6 允许测试源集使用），不起 Spring 上下文。
 * 脚本动态写入临时目录，与生产脚本目录（src/main/resources/db/migration）物理隔离；
 * 隔离口径（DB-25 起，ADR-010 §8 形态 B）：模块共享容器 + <b>每用例独立库名</b>——护栏用例都用 V1 起步，
 * 共用库会让 flyway_schema_history 撞版本号（实测教训，见 WBS-2.4.11 开发日志）；
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过——门禁不红。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayGuardrailTest {

    private Flyway flywayFor(final Path location, final String database) {
        return Flyway.configure()
                .dataSource(SharedMySqlContainer.jdbcUrlFor(database),
                        SharedMySqlContainer.container().getUsername(),
                        SharedMySqlContainer.container().getPassword())
                .locations("filesystem:" + location.toAbsolutePath().toString().replace('\\', '/'))
                .outOfOrder(false)
                .load();
    }

    private static Path writeScript(final Path dir, final String fileName, final String sql) throws Exception {
        final Path file = dir.resolve(fileName);
        Files.write(file, sql.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private int appliedCount(final String version, final String database) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                SharedMySqlContainer.jdbcUrlFor(database),
                SharedMySqlContainer.container().getUsername(),
                SharedMySqlContainer.container().getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = 1")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * 乱序拒绝（ADR-009 规则 7 自动回归；实测口径更正见 WBS-2.4.11 hifi 护栏契约表）：
     * history 已应用 V1/V3 后补入低版本 V2 脚本——validateOnMigrate 默认开启下，
     * 下一次 migrate() 抛 FlywayValidateException（Detected resolved migration not applied to database: 2），
     * 服务启动 fail-fast（强于"忽略并告警"），乱序脚本永不静默入库。
     */
    @Test
    void outOfOrderScriptRejectedFailFast() throws Exception {
        final String database = "ctds_guardrail_ordering";
        final Path dir = Files.createTempDirectory("ctds-flyway-ooo");
        writeScript(dir, "V1__base.sql", "CREATE TABLE t_ooo_base (id INT PRIMARY KEY);");
        writeScript(dir, "V3__later.sql", "CREATE TABLE t_ooo_later (id INT PRIMARY KEY);");
        final Flyway flyway = flywayFor(dir, database);
        flyway.migrate();
        assertEquals(1, appliedCount("1", database));
        assertEquals(1, appliedCount("3", database));

        writeScript(dir, "V2__late.sql", "CREATE TABLE t_ooo_late (id INT PRIMARY KEY);");
        final FlywayValidateException ex = assertThrows(FlywayValidateException.class, flyway::migrate);
        assertTrue(ex.getMessage().contains("Detected resolved migration not applied to database: 2"),
                "异常消息应指明乱序脚本版本 2 未应用，实际：" + ex.getMessage());
        assertEquals(0, appliedCount("2", database));
    }

    /**
     * 篡改 fail-fast（ADR-009 规则 5 自动回归，2.4.10 人工演练第④步升级）：
     * V1 应用后被修改，再 migrate() 抛 FlywayValidateException（消息含 Migration checksum mismatch）。
     */
    @Test
    void tamperedAppliedScriptFailsFast() throws Exception {
        final String database = "ctds_guardrail_tamper";
        final Path dir = Files.createTempDirectory("ctds-flyway-tamper");
        final Path v1 = writeScript(dir, "V1__origin.sql", "CREATE TABLE t_tamper (id INT PRIMARY KEY);");
        flywayFor(dir, database).migrate();
        assertEquals(1, appliedCount("1", database));

        Files.write(v1, ("CREATE TABLE t_tamper (id INT PRIMARY KEY);\n"
                + "-- 已应用脚本被篡改（校验和防护演示）\n").getBytes(StandardCharsets.UTF_8));
        final FlywayValidateException ex =
                assertThrows(FlywayValidateException.class, () -> flywayFor(dir, database).migrate());
        assertTrue(ex.getMessage().contains("checksum"),
                "异常消息应含 checksum（校验和不符），实际：" + ex.getMessage());
        assertEquals(1, appliedCount("1", database),
                "fail-fast 后 V1 应保持已应用成功记录（不产生部分迁移/回写）");
    }
}
