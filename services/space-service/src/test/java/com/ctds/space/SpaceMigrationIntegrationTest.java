package com.ctds.space;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.space.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 空间库表迁移与约束探针（WBS-3.2.2 hifi §4 测试计划；任务卡 §三 探针 0~6）。
 *
 * <p>真实 MySQL 8 容器实跑（不用 H2 兜底——生成列/IF 方言行为必须真库实证，沿 2.4.11 教训）；
 * 每个用例独立库名执行全新迁移（隔离语义等价方法级容器，沿 ADR-010 §3.6"独立 schema"口径）。
 * 容器经 {@link com.ctds.space.support.SharedMySqlContainer} 模块级共享（评审循环 1 收敛：
 * 消除类级容器字段注解的第二容器，ADR-010 §8 形态 B + 守卫约束）。三条唯一性硬约束的反向探针：
 * 规格规则不允许只有应用层判定，DB 兜底的失败路径必须有测试（任务卡 Q5/Q6-A）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class SpaceMigrationIntegrationTest {

    /** 独立库名计数器（每用例一库，库由 {@link com.ctds.space.support.SharedMySqlContainer} 建备，命名受控）。 */
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
        // 迁移历史：V1 建表（3.2.2）+ V2 留痕表动作码登记与值列放宽（3.2.3）——随迁移集演进（V3+ 只增不减）
        assertTrue(count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1") >= 2,
                "迁移历史应至少含 V1+V2 两次成功迁移");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1"),
                "V2 迁移应在位");
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

    /** WBS-3.2.5 T17：V3 迁移——平台级基线种子 3 条（两红线一非红线）+ POLICY_DEFINE 动作码注释登记。 */
    @Test
    void v3SeedsPlatformBaselineAndRegistersPolicyDefineAction() throws SQLException {
        final String db = freshDatabaseWithMigration("policy_seed");
        // 种子齐备：目录三键各恰一条平台级 ACTIVE 条目（红色标记 1/1/0——C-2.3 剧本 S2 演示载体）
        assertEquals(3, count(db, "SELECT COUNT(*) FROM space_policy WHERE scope = 'PLATFORM' "
                + "AND status = 'ACTIVE'"), "平台级 ACTIVE 条目应恰为种子 3 条");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM space_policy WHERE scope = 'PLATFORM' "
                + "AND entry_key = 'data.visibility' AND entry_value = 'SPACE_MEMBER' AND is_redline = 1"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM space_policy WHERE scope = 'PLATFORM' "
                + "AND entry_key = 'data.retention' AND entry_value = 'D90' AND is_redline = 1"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM space_policy WHERE scope = 'PLATFORM' "
                + "AND entry_key = 'member.data_export' AND entry_value = 'ALLOWED' AND is_redline = 0"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM space_policy WHERE space_id IS NOT NULL"),
            "V3 只种平台级条目，不得产生空间级行");
        // 动作码登记：space_action_log.action 注释含 POLICY_DEFINE（沿 V2 登记口径）
        assertTrue(scalar(db, "SELECT column_comment FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_action_log' AND column_name = 'action'", db)
                .contains("POLICY_DEFINE"), "action 注释应登记 POLICY_DEFINE 动作码");
    }

    /** DB-29：V4 迁移——GOVERNANCE_VIEW 动作码注释登记（运营档治理查看留痕；零破坏：无新表无列变更）。 */
    @Test
    void v4RegistersGovernanceViewAction() throws SQLException {
        final String db = freshDatabaseWithMigration("governance_view");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM flyway_schema_history"
                + " WHERE version = '4' AND success = 1"), "V4 迁移应在位");
        final String comment = scalar(db, "SELECT column_comment FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_action_log' AND column_name = 'action'", db);
        assertTrue(comment.contains("GOVERNANCE_VIEW"), "action 注释应登记 GOVERNANCE_VIEW 动作码");
        assertTrue(comment.contains("POLICY_DEFINE"), "既有动作码登记不得回退");
        assertTrue(comment.contains("UPDATE="), "既有动作码登记不得回退（V2 UPDATE）");
        // 零破坏：表集合不变（六表）、列集合不变、action 列类型保持（评审④ P3-4 护栏补强）
        assertEquals(6, count(db, "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_name IN ('space','space_name_lock','space_member',"
                + "'space_admission','space_policy','space_action_log')", db), "V4 不得新增/删除表");
        final String columnType = scalar(db, "SELECT column_type FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_action_log' AND column_name = 'action'", db);
        assertEquals("varchar(32)", columnType, "action 列类型应保持不变");
        assertEquals("NO", scalar(db, "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'space_action_log' AND column_name = 'action'", db),
                "action 列应保持 NOT NULL");
    }

    // ---------- 助手：每用例独立库 + 迁移 + 纯 JDBC 断言 ----------

    /** 建独立库并在其上执行全新 V1~V4 迁移（locations 全量自动前滚，随迁移目录演进），返回库名（隔离语义等价方法级容器）。
     * 建库/授权由 {@link com.ctds.space.support.SharedMySqlContainer} 以容器 root 完成
     * （应用用户对新库无 CREATE 权限，授权后 Flyway/断言仍以应用用户接入）。 */
    private String freshDatabaseWithMigration(final String label) {
        final String db = "ctds_probe_" + label + "_" + DB_SEQ.incrementAndGet();
        Flyway.configure()
                .dataSource(SharedMySqlContainer.jdbcUrlFor(db), SharedMySqlContainer.container().getUsername(),
                        SharedMySqlContainer.container().getPassword())
                .locations("classpath:db/migration").load().migrate();
        return db;
    }

    /** 探针 SQL 直接执行（异常原样上抛——约束冲突探针须断言到原始 SQLIntegrityConstraintViolationException）。 */
    private void exec(final String db, final String sql, final Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(SharedMySqlContainer.jdbcUrlFor(db),
                SharedMySqlContainer.container().getUsername(), SharedMySqlContainer.container().getPassword());
                PreparedStatement ps = c.prepareStatement(sql)) {
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
        try (Connection c = DriverManager.getConnection(SharedMySqlContainer.jdbcUrlFor(db),
                SharedMySqlContainer.container().getUsername(), SharedMySqlContainer.container().getPassword());
                PreparedStatement ps = c.prepareStatement(sql)) {
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
        try (Connection c = DriverManager.getConnection(SharedMySqlContainer.jdbcUrlFor(db),
                SharedMySqlContainer.container().getUsername(), SharedMySqlContainer.container().getPassword());
                PreparedStatement ps = c.prepareStatement(
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
