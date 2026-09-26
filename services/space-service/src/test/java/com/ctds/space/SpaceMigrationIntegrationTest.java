package com.ctds.space;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 空间库表迁移与约束探针（WBS-3.2.2 hifi §4 测试计划；任务卡 §三 探针 0~6）。
 *
 * <p>真实 MySQL 8 容器实跑（不用 H2 兜底——生成列/IF 方言行为必须真库实证，沿 2.4.11 教训）；
 * 每个用例独立库名执行全新迁移（隔离语义等价方法级容器，沿 ADR-010 §3.6"独立 schema"口径）。
 * 三条唯一性硬约束的反向探针：规格规则不允许只有应用层判定，DB 兜底的失败路径必须有测试（任务卡 Q5/Q6-A）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class SpaceMigrationIntegrationTest {

    /** 显式镜像标签 mysql:8.0（ADR-010 §3.2 禁止 latest）；类级启动一次，用例间以独立库名隔离。 */
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_bootstrap");

    /** 独立库名计数器（每用例一库，库由本类创建，命名受控无注入面）。 */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    /** 探针 0：迁移冒烟——空库执行 V1 成功，六表齐备且迁移历史成功。 */
    @Test
    void migrationSmokeAllSixTablesCreated() throws SQLException {
        final String db = freshDatabaseWithMigration("smoke");
        for (final String table : new String[] {"space", "space_name_lock", "space_member",
                "space_admission", "space_policy", "space_action_log"}) {
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, table), "表应存在：" + table);
        }
        assertEquals(1, count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"));
    }

    /** 探针 1：同一所有者活跃同名第二行被拒（规格行为 1 规则 3，uk_owner_norm_name）。 */
    @Test
    void sameOwnerSameActiveNameRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("same_owner");
        insertSpace(db, "普惠金融空间", "普惠金融空间", "S1001", "ACTIVE");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertSpace(db, "  普惠金融空间 ", "普惠金融空间", "S1001", "ACTIVE"));
    }

    /** 探针 2：不同所有者活跃同名允许（不加严实证——"同一所有者"口径，任务卡 Q5-A）。 */
    @Test
    void crossOwnerSameActiveNameAllowed() throws SQLException {
        final String db = freshDatabaseWithMigration("cross_owner");
        insertSpace(db, "普惠金融空间", "普惠金融空间", "S1001", "ACTIVE");
        assertDoesNotThrow(() -> insertSpace(db, "普惠金融空间", "普惠金融空间", "S1002", "ACTIVE"));
    }

    /** 探针 3：解散名称全平台锁定（规格行为 2 规则 4，space_name_lock PK 兜底）。 */
    @Test
    void dissolvedNameGloballyLocked() throws SQLException {
        final String db = freshDatabaseWithMigration("name_lock");
        insertSpace(db, "医疗验证空间", "医疗验证空间", "S1001", "DISSOLVED");
        exec(db, "INSERT INTO space_name_lock (normalized_name, space_id) VALUES (?, ?)",
                "医疗验证空间", 1);
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> exec(db, "INSERT INTO space_name_lock (normalized_name, space_id) VALUES (?, ?)",
                        "医疗验证空间", 2));
    }

    /** 探针 4：同空间同主体唯一活跃关系 + 唯一活跃所有者（行为 3 规则 4 / 行为 5 规则 3）。 */
    @Test
    void memberActiveUniquenessAndSingleOwner() throws SQLException {
        final String db = freshDatabaseWithMigration("member");
        insertSpace(db, "探针空间", "探针空间", "S1001", "ACTIVE");
        insertMember(db, 1, "S1001", "OWNER", "ACTIVE");
        insertMember(db, 1, "S1002", "MEMBER", "ACTIVE");
        // 同空间同主体第二条生效关系被拒（uk_active_member）
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertMember(db, 1, "S1002", "MEMBER", "ACTIVE"));
        // 生效关系之外允许保留终态历史行（ACTIVE + LEFT 共存，生成列 NULL 不参与唯一去重）
        assertDoesNotThrow(() -> insertMember(db, 1, "S1002", "MEMBER", "LEFT"));
        // 第二个活跃所有者被拒（uk_active_owner，唯一所有者保护硬兜底）
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertMember(db, 1, "S1003", "OWNER", "ACTIVE"));
        // 所有者退出（终态）后同空间可产生新的活跃所有者（唯一所有者保护语义：至多一个活跃）
        exec(db, "UPDATE space_member SET status = 'LEFT', exited_at = NOW() WHERE space_id = 1"
                + " AND subject_no = 'S1001'");
        assertDoesNotThrow(() -> insertMember(db, 1, "S1003", "OWNER", "ACTIVE"));
    }

    /** 探针 5：策略载体唯一——同作用域同键至多一条 ACTIVE，归档多行共存（行为 7 载体约束）。 */
    @Test
    void policyCarrierUniqueness() throws SQLException {
        final String db = freshDatabaseWithMigration("policy");
        insertPolicy(db, "PLATFORM", null, "准入门槛", "ADMITTED", 1, "ACTIVE");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertPolicy(db, "PLATFORM", null, "准入门槛", "ADMITTED", 1, "ACTIVE"));
        insertPolicy(db, "SPACE", 1L, "准入门槛", "ADMITTED", 0, "ACTIVE");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertPolicy(db, "SPACE", 1L, "准入门槛", "更严值", 0, "ACTIVE"));
        assertDoesNotThrow(() -> {
            insertPolicy(db, "PLATFORM", null, "准入门槛", "旧值", 0, "ARCHIVED");
            insertPolicy(db, "PLATFORM", null, "准入门槛", "旧值2", 0, "ARCHIVED");
        });
    }

    /** 探针 6：文档化承诺可核对——表/列注释在位、生成列为 STORED GENERATED（information_schema 实查）。 */
    @Test
    void commentsAndGeneratedColumnsInPlace() throws SQLException {
        final String db = freshDatabaseWithMigration("docs");
        assertEquals("空间表（一行=一个逻辑空间；活跃空间名称同一所有者唯一，解散名称全平台锁定见 space_name_lock）",
                scalar(db, "SELECT table_comment FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_name = 'space'", db));
        assertTrue(scalar(db, "SELECT extra FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'space_member' AND column_name = 'active_flag'", db).contains("GENERATED"),
                "active_flag 应为生成列");
        assertTrue(scalar(db, "SELECT generation_expression FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_member' AND column_name = 'owner_uniq'", db)
                .toUpperCase().contains("OWNER"), "owner_uniq 生成表达式应含 OWNER 判定");
        assertTrue(scalar(db, "SELECT generation_expression FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_policy' AND column_name = 'scope_uniq'", db)
                .toUpperCase().contains("PLATFORM"), "scope_uniq 生成表达式应含 PLATFORM 分支");
    }

    // ---------- 助手：每用例独立库 + 迁移 + 纯 JDBC 断言 ----------

    /** 建独立库并在其上执行全新 V1 迁移，返回库名（隔离语义等价方法级容器）。
     * 建库与授权用容器 root（沿 SharedMySqlContainer 先例：应用用户对新库无 CREATE 权限，
     * 授权后 Flyway/断言仍以应用用户接入）。 */
    private String freshDatabaseWithMigration(final String label) {
        final String db = "ctds_probe_" + label + "_" + DB_SEQ.incrementAndGet();
        if (!db.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("非法库名：" + db);
        }
        execRoot("CREATE DATABASE IF NOT EXISTS `" + db + "`");
        execRoot("GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + MYSQL.getUsername() + "'@'%'");
        Flyway.configure().dataSource(urlFor(db), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return db;
    }

    private String urlFor(final String db) {
        return MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + db);
    }

    private void execRoot(final String ddl) {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root",
                MYSQL.getPassword()); Statement s = c.createStatement()) {
            s.execute(ddl);
        } catch (final SQLException e) {
            throw new IllegalStateException("容器 root 连接执行失败：" + ddl + "（" + e.getMessage() + "）", e);
        }
    }

    /** 探针 SQL 直接执行（异常原样上抛——约束冲突探针须断言到原始 SQLIntegrityConstraintViolationException）。 */
    private void exec(final String db, final String sql, final Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(urlFor(db), MYSQL.getUsername(),
                MYSQL.getPassword()); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private int count(final String db, final String sql, final Object... args) throws SQLException {
        return Integer.parseInt(scalar(db, sql, args));
    }

    /** 单值查询；无行返回空串（断言侧自行处理）。 */
    private String scalar(final String db, final String sql, final Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(urlFor(db), MYSQL.getUsername(),
                MYSQL.getPassword()); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? String.valueOf(rs.getObject(1)) : "";
            }
        }
    }

    private void insertSpace(final String db, final String name, final String normalizedName,
            final String ownerSubjectNo, final String status) throws SQLException {
        exec(db, "INSERT INTO space (name, normalized_name, scene_type, access_mode, visibility,"
                + " owner_subject_no, status) VALUES (?, ?, 'FINTECH', 'OPEN', 'PUBLIC', ?, ?)",
                name, normalizedName, ownerSubjectNo, status);
    }

    private void insertMember(final String db, final long spaceId, final String subjectNo,
            final String role, final String status) throws SQLException {
        exec(db, "INSERT INTO space_member (space_id, subject_no, role, status) VALUES (?, ?, ?, ?)",
                spaceId, subjectNo, role, status);
    }

    private void insertPolicy(final String db, final String scope, final Long spaceId,
            final String entryKey, final String entryValue, final int redline, final String status)
            throws SQLException {
        exec(db, "INSERT INTO space_policy (scope, space_id, entry_key, entry_value, is_redline,"
                + " status) VALUES (?, ?, ?, ?, ?, ?)", scope, spaceId, entryKey, entryValue,
                redline, status);
    }
}
