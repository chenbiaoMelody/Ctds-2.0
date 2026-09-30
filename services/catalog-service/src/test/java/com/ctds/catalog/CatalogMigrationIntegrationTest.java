package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.catalog.support.SharedMySqlContainer;
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
 * 资源库表迁移与约束探针（WBS-3.3.2 hifi §3 + §6 T11；任务卡关闭条件②的库侧承诺）。
 *
 * <p>真实 MySQL 8 容器实跑（不用 H2 兜底——生成列/唯一索引行为必须真库实证，沿 2.4.11 教训）；
 * 每个用例独立库名执行全新迁移（隔离语义等价方法级容器，沿 ADR-010 §3.6"独立 schema"口径）。
 * 三条唯一性硬约束的反向探针：规格规则不允许只有应用层判定，DB 兜底的失败路径必须有测试
 * （uk_data_no / uk_space_norm_name / dataset_name_lock 复合 PK）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogMigrationIntegrationTest {

    /** 独立库名计数器（每用例一库，命名受控）。 */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    /** hifi §3 逐表列清单（键 = 表名；值 = 按 ordinal_position 的列序）——"列齐"为建表正确性核心承诺。 */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = buildExpectedColumns();

    /** hifi §3 表注释（以 V1 迁移 SQL 注释为准的文档化承诺，逐表核对）。 */
    private static final Map<String, String> EXPECTED_TABLE_COMMENTS = buildExpectedTableComments();

    private static Map<String, List<String>> buildExpectedColumns() {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("dataset", List.of("id", "data_no", "space_id", "owner_subject_no", "name",
                "normalized_name", "type", "intro", "semantic_tags", "declare_category", "declare_level",
                "declare_important", "status", "created_at", "updated_at"));
        columns.put("dataset_name_lock", List.of("space_id", "normalized_name", "dataset_id", "locked_at"));
        columns.put("dataset_action_log", List.of("id", "actor_subject_no", "space_id", "dataset_id",
                "action", "from_value", "to_value", "result", "reason_code", "created_at"));
        columns.put("dataset_no_seq", List.of("seq_date", "seq_key", "seq_value"));
        return columns;
    }

    private static Map<String, String> buildExpectedTableComments() {
        final Map<String, String> comments = new LinkedHashMap<>();
        comments.put("dataset", "资源主表（一行=一个数据资源；数据标识全平台唯一；同空间归一化名唯一（活跃与注销行共同参与））");
        comments.put("dataset_name_lock", "注销资源名称同空间锁定表（行为2规则3；复合 PK 硬约束兜底）");
        comments.put("dataset_action_log", "资源域统一操作留痕（四要素：谁/何时/对象/动作+结果与理由码；拒绝动作同样留痕；不含敏感原文；只插不改）");
        comments.put("dataset_no_seq", "数据标识当日序号取号表（原子自增、当日重置；Q3-A）");
        return comments;
    }

    // ==== 探针 1：迁移冒烟——四表齐备、列齐（含顺序）、表注释与迁移历史 ====

    @Test
    void migrationSmokeAllFourTablesCreated() throws SQLException {
        final String db = freshDatabaseWithMigration("smoke");
        for (final Map.Entry<String, List<String>> expected : EXPECTED_COLUMNS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, expected.getKey()))
                    .as("表应存在：" + expected.getKey()).isEqualTo(1);
            assertThat(columnNames(db, expected.getKey())).as("列齐且顺序一致：" + expected.getKey())
                    .isEqualTo(expected.getValue());
        }
        for (final Map.Entry<String, String> expected : EXPECTED_TABLE_COMMENTS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                            + "AND table_name = ? AND table_comment = ?", db, expected.getKey(),
                    expected.getValue()))
                    .as("表注释一致：" + expected.getKey()).isEqualTo(1);
        }
        assertThat(count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1"))
                .as("V1 迁移应在位且成功").isEqualTo(1);
    }

    // ==== 探针 2：唯一索引硬约束在场（uk_data_no / uk_space_norm_name / name_lock 复合 PK）====

    @Test
    void uniqueIndexesPresent() throws SQLException {
        final String db = freshDatabaseWithMigration("indexes");
        assertThat(indexColumns(db, "dataset", "uk_data_no")).containsExactly("data_no");
        assertThat(indexColumns(db, "dataset", "uk_space_norm_name"))
                .containsExactlyInAnyOrder("space_id", "normalized_name");
        assertThat(indexColumns(db, "dataset_name_lock", "PRIMARY"))
                .containsExactlyInAnyOrder("space_id", "normalized_name");
        // 判重前提：列排序规则为 0900_ai_ci（大小写/重音不敏感，"过阻断"方向）
        assertThat(collation(db, "dataset", "normalized_name")).isEqualTo("utf8mb4_0900_ai_ci");
    }

    // ==== 探针 3：数据标识全平台唯一（反向探针）====

    @Test
    void dataNoDuplicateRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("data_no_dup");
        insertDataset(db, "DS20260930000001", 2001L, "重复标识数据集一", "ACTIVE");
        assertThatThrownBy(() -> insertDataset(db, "DS20260930000001", 2002L, "重复标识数据集二", "ACTIVE"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    // ==== 探针 4：同空间归一化名唯一（反向探针；注销行同样参与——DB 兜底）====

    @Test
    void sameSpaceNormalizedNameDuplicateRejectedAcrossStatuses() throws SQLException {
        final String db = freshDatabaseWithMigration("norm_dup");
        insertDataset(db, "DS20260930000002", 2101L, "普惠金融数据集", "ACTIVE");
        // 同空间同名（不同原始名归一化后一致由应用层保证，本探针证 DB 键参与）
        assertThatThrownBy(() -> insertDataset(db, "DS20260930000003", 2101L, "普惠金融数据集", "ACTIVE"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 注销行同样参与唯一键（活跃行与注销行共同锁定同空间归一化名）
        assertThat(count(db, "SELECT COUNT(*) FROM dataset WHERE status = 'DELETED'")).isZero();
        insertDataset(db, "DS20260930000005", 2103L, "已注销数据集", "DELETED");
        assertThatThrownBy(() -> insertDataset(db, "DS20260930000006", 2103L, "已注销数据集", "ACTIVE"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 跨空间同名允许（不加严）
        insertDataset(db, "DS20260930000004", 2102L, "普惠金融数据集", "ACTIVE");
        assertThat(count(db, "SELECT COUNT(*) FROM dataset WHERE normalized_name = '普惠金融数据集'"))
                .isEqualTo(2);
    }

    // ==== 探针 5：注销名称锁定表复合 PK（反向探针）====

    @Test
    void nameLockCompositePrimaryKeyRejectsDuplicate() throws SQLException {
        final String db = freshDatabaseWithMigration("name_lock");
        insertNameLock(db, 2201L, "待注销数据集", 1L);
        assertThatThrownBy(() -> insertNameLock(db, 2201L, "待注销数据集", 2L))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 同空间不同名、不同空间同名均允许（口径 = 同空间）
        insertNameLock(db, 2201L, "其他数据集", 3L);
        insertNameLock(db, 2202L, "待注销数据集", 4L);
        assertThat(count(db, "SELECT COUNT(*) FROM dataset_name_lock")).isEqualTo(3);
    }

    // ==== 探针 6：留痕表值列宽度（from_value/to_value VARCHAR(1024)——变更摘要可容纳）====

    @Test
    void actionLogValueColumnsWideEnough() throws SQLException {
        final String db = freshDatabaseWithMigration("log_width");
        final String longValue = "intro:" + "长".repeat(600);
        assertThat(count(db, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'dataset_action_log' AND column_name = 'from_value' "
                + "AND character_maximum_length = 1024", db)).isEqualTo(1);
        insertLog(db, longValue);
        assertThat(count(db, "SELECT COUNT(*) FROM dataset_action_log WHERE from_value = ?", longValue))
                .isEqualTo(1);
    }

    // ==== 助手 ====

    private static String freshDatabaseWithMigration(final String tag) {
        final String database = "ctds_catalog_" + tag + "_" + DB_SEQ.incrementAndGet();
        Flyway.configure()
                .dataSource(SharedMySqlContainer.jdbcUrlFor(database),
                        SharedMySqlContainer.container().getUsername(),
                        SharedMySqlContainer.container().getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return database;
    }

    private static Connection connect(final String database) throws SQLException {
        return DriverManager.getConnection(SharedMySqlContainer.jdbcUrlFor(database),
                SharedMySqlContainer.container().getUsername(),
                SharedMySqlContainer.container().getPassword());
    }

    private static long count(final String database, final String sql, final Object... params)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> columnNames(final String database, final String table) throws SQLException {
        return stringList(database, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position", database, table);
    }

    private static List<String> indexColumns(final String database, final String table,
            final String indexName) throws SQLException {
        return stringList(database, "SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = ? AND table_name = ? AND index_name = ? ORDER BY seq_in_index",
                database, table, indexName);
    }

    private static String collation(final String database, final String table, final String column)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT collation_name FROM information_schema.columns WHERE table_schema = ? "
                             + "AND table_name = ? AND column_name = ?")) {
            bind(statement, new Object[] {database, table, column});
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static List<String> stringList(final String database, final String sql, final Object... params)
            throws SQLException {
        final List<String> values = new ArrayList<>();
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    private static void bind(final PreparedStatement statement, final Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private static void insertDataset(final String database, final String dataNo, final long spaceId,
            final String normalizedName, final String status) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, type, "
                             + "intro, semantic_tags, declare_category, declare_level, status) "
                             + "VALUES (?, ?, 'S1', ?, ?, 'DATASET', '简介', '[\"金融\"]', '金融', 'L2', ?)")) {
            bind(statement, new Object[] {dataNo, spaceId, normalizedName, normalizedName, status});
            statement.executeUpdate();
        }
    }

    private static void insertNameLock(final String database, final long spaceId, final String normalizedName,
            final long datasetId) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dataset_name_lock (space_id, normalized_name, dataset_id) VALUES (?, ?, ?)")) {
            bind(statement, new Object[] {spaceId, normalizedName, datasetId});
            statement.executeUpdate();
        }
    }

    private static void insertLog(final String database, final String fromValue) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dataset_action_log (actor_subject_no, space_id, action, from_value, "
                             + "result) VALUES ('S1', 3001, 'UPDATE', ?, 'SUCCESS')")) {
            bind(statement, new Object[] {fromValue});
            statement.executeUpdate();
        }
    }
}
