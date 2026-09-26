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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** hifi §1 逐表列清单（键 = 表名；值 = 按 ordinal_position 的列序）——"列齐"为建表正确性核心承诺。 */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = buildExpectedColumns();

    /** hifi §1 六表表注释（以 V1 迁移 SQL 注释为准的文档化承诺，探针 6 逐表核对）。 */
    private static final Map<String, String> EXPECTED_TABLE_COMMENTS = buildExpectedTableComments();

    /** 硬约束载体列与代表性列注释（hifi §4 探针 6"列 COMMENT"承诺，探针 6 逐列核对）。 */
    private static final Map<String, String> EXPECTED_COLUMN_COMMENTS = buildExpectedColumnComments();

    private static Map<String, List<String>> buildExpectedColumns() {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("space", List.of("id", "name", "normalized_name", "scene_type", "access_mode",
                "visibility", "intro", "effective_from", "effective_to", "owner_subject_no",
                "status", "created_at", "updated_at"));
        columns.put("space_name_lock", List.of("normalized_name", "space_id", "locked_at"));
        columns.put("space_member", List.of("id", "space_id", "subject_no", "role", "status",
                "joined_at", "exited_at", "active_flag", "owner_uniq", "created_at", "updated_at"));
        columns.put("space_admission", List.of("id", "space_id", "subject_no", "type", "status",
                "operator", "reason", "member_id", "created_at", "updated_at"));
        columns.put("space_policy", List.of("id", "scope", "space_id", "platform_entry_id",
                "entry_key", "entry_value", "is_redline", "status", "scope_uniq",
                "created_at", "updated_at"));
        columns.put("space_action_log", List.of("id", "space_id", "target_type", "target_id",
                "action", "operator", "from_value", "to_value", "result", "reason", "created_at"));
        return columns;
    }

    private static Map<String, String> buildExpectedTableComments() {
        final Map<String, String> comments = new LinkedHashMap<>();
        comments.put("space", "空间表（一行=一个逻辑空间；活跃空间名称同一所有者唯一，解散名称全平台锁定见 space_name_lock）");
        comments.put("space_name_lock", "解散空间名称全平台锁定表（行为2规则4；PK 硬约束兜底）");
        comments.put("space_member", "空间成员表（一行=一条成员关系；同一空间同一主体至多一条生效关系、至多一个活跃所有者均由唯一索引硬兜底；退出/移除行保留改终态）");
        comments.put("space_admission", "空间准入单（申请/邀请载体：未确认邀请与未审批申请不产生成员关系；通过后回填 member_id 贯通追溯）");
        comments.put("space_policy", "空间策略条目载体表（平台级默认+空间级覆盖；条目键与值语义归 3.2.5 策略继承引擎定稿；红线=不得放宽标记；历史版本走留痕）");
        comments.put("space_action_log", "空间域统一操作留痕（四要素：谁/何时/对象/动作+结果与理由；拒绝动作同样留痕；不含敏感原文；只插不改）");
        return comments;
    }

    private static Map<String, String> buildExpectedColumnComments() {
        final Map<String, String> comments = new LinkedHashMap<>();
        comments.put("space.normalized_name", "归一化名称（去首尾空白与控制字符，唯一性判定口径）");
        comments.put("space.owner_subject_no",
                "所有者主体编号（逻辑引用 ctds_subject.subject.subject_no；ADMITTED 资格由应用层调 C-1.1 判定，本库不存副本）");
        comments.put("space_member.active_flag", "活跃标志生成列（ACTIVE=1 其余 NULL，支撑同空间同主体至多一条生效关系）");
        comments.put("space_member.owner_uniq", "唯一所有者生成列（活跃 OWNER=1 其余 NULL，支撑同空间至多一个活跃所有者——行为5规则3 硬兜底）");
        comments.put("space_policy.scope_uniq",
                "作用域唯一生成列（平台级 ACTIVE 记 0、空间级 ACTIVE 记 space_id、归档 NULL——支撑同作用域同键至多一条 ACTIVE）");
        comments.put("space_action_log.result", "结果：SUCCESS/DENIED（拒绝同样留痕）");
        return comments;
    }

    /** 探针 0：迁移冒烟——空库执行迁移脚本成功，六表齐备且逐表"列齐"（含顺序）、迁移历史成功。 */
    @Test
    void migrationSmokeAllSixTablesCreated() throws SQLException {
        final String db = freshDatabaseWithMigration("smoke");
        for (final Map.Entry<String, List<String>> expected : EXPECTED_COLUMNS.entrySet()) {
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, expected.getKey()),
                    "表应存在：" + expected.getKey());
            assertEquals(expected.getValue(), columnNames(db, expected.getKey()),
                    "表列应齐备且顺序与 hifi §1 一致：" + expected.getKey());
        }
        // 迁移历史 = V1 建表（3.2.2）+ V2 留痕表动作码登记与值列放宽（3.2.3），随迁移集演进
        assertEquals(2, count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"));
    }

    /** 探针 1：同一所有者活跃同名第二行被拒（规格行为 1 规则 3，uk_owner_norm_name）。 */
    @Test
    void sameOwnerSameActiveNameRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("same_owner");
        // name 传原始未归一化串仅示意"原始输入"：DB 不做归一化（V1 头注"只存结果"），唯一性判定键是
        // 应用层写入的 normalized_name（归一化实现归 3.2.3）——本探针只证 normalized_name 参与唯一索引。
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
        // 第二条终态行（REMOVED）亦共存——钉死"NULL 不参与去重"语义：两条终态行 + 一条活跃行并存
        //（若 active_flag 误写为 IF(...,1,0) 非 NULL 形态，此处即红，评审④变异推演补的对称保护）
        assertDoesNotThrow(() -> insertMember(db, 1, "S1002", "MEMBER", "REMOVED"));
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

    /** 探针 6：文档化承诺可核对——六表注释、代表性列注释、生成列定义在位（information_schema 实查）。 */
    @Test
    void commentsAndGeneratedColumnsInPlace() throws SQLException {
        final String db = freshDatabaseWithMigration("docs");
        for (final Map.Entry<String, String> expected : EXPECTED_TABLE_COMMENTS.entrySet()) {
            assertEquals(expected.getValue(), scalar(db, "SELECT table_comment FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, expected.getKey()),
                    "表注释应在位：" + expected.getKey());
        }
        for (final Map.Entry<String, String> expected : EXPECTED_COLUMN_COMMENTS.entrySet()) {
            final String[] parts = expected.getKey().split("\\.", 2);
            assertEquals(expected.getValue(), scalar(db, "SELECT column_comment FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = ? AND column_name = ?", db, parts[0], parts[1]),
                    "列注释应在位：" + expected.getKey());
        }
        assertTrue(scalar(db, "SELECT extra FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'space_member' AND column_name = 'active_flag'", db).contains("GENERATED"),
                "active_flag 应为生成列");
        assertTrue(scalar(db, "SELECT generation_expression FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_member' AND column_name = 'active_flag'", db)
                .toUpperCase().contains("ACTIVE"), "active_flag 生成表达式应含 ACTIVE 判定");
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
        // 用户名白名单防御沿 SharedMySqlContainer 先例（GRANT 语句拼接前的纵深防御，评审②补齐）
        final String appUser = MYSQL.getUsername();
        if (!appUser.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("非法数据库用户名：" + appUser);
        }
        execRoot("CREATE DATABASE IF NOT EXISTS `" + db + "`");
        execRoot("GRANT ALL PRIVILEGES ON `" + db + "`.* TO '" + appUser + "'@'%'");
        Flyway.configure().dataSource(urlFor(db), appUser, MYSQL.getPassword())
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

    /** 按 ordinal_position 返回表列名序列（information_schema 实查，探针 0"列齐"断言用）。 */
    private List<String> columnNames(final String db, final String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(urlFor(db), MYSQL.getUsername(),
                MYSQL.getPassword()); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                final List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return names;
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
