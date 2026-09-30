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

    /** WBS-3.3.3 hifi §3 V2 受控词表两表列清单（按 ordinal_position 的列序）。 */
    private static final Map<String, List<String>> V2_EXPECTED_COLUMNS = buildV2ExpectedColumns();

    /** WBS-3.3.3 hifi §3 V2 受控词表两表注释（以 V2 迁移 SQL 注释为准）。 */
    private static final Map<String, String> V2_EXPECTED_TABLE_COMMENTS = buildV2ExpectedTableComments();

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

    private static Map<String, List<String>> buildV2ExpectedColumns() {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("tag_vocabulary",
                List.of("id", "vocabulary_code", "vocabulary_name", "created_at"));
        columns.put("tag_term", List.of("id", "vocabulary_id", "term_code", "term_name",
                "normalized_term", "created_at"));
        return columns;
    }

    private static Map<String, String> buildV2ExpectedTableComments() {
        final Map<String, String> comments = new LinkedHashMap<>();
        comments.put("tag_vocabulary", "语义标签受控词表册（行为1规则3受控词表选取；V1.0 随迁移内置、只读面，无维护写面）");
        comments.put("tag_term", "受控词条（归一化名经应用侧 DatasetNameNormalizer 产出；同册归一化名唯一 = 成员校验口径唯一来源）");
        return comments;
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

    // ==== 探针 7：V2 受控词表两表齐备、列齐、注释与迁移历史在位（WBS-3.3.3 hifi §3）====

    @Test
    void tagVocabularyMigrationCreatesTwoTablesWithExpectedShape() throws SQLException {
        final String db = freshDatabaseWithMigration("vocab_shape");
        for (final Map.Entry<String, List<String>> expected : V2_EXPECTED_COLUMNS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, expected.getKey()))
                    .as("表应存在：" + expected.getKey()).isEqualTo(1);
            assertThat(columnNames(db, expected.getKey())).as("列齐且顺序一致：" + expected.getKey())
                    .isEqualTo(expected.getValue());
        }
        assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                        + "AND table_name = ? AND table_comment = ?", db, "tag_vocabulary",
                V2_EXPECTED_TABLE_COMMENTS.get("tag_vocabulary")))
                .as("词表册表注释一致").isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                        + "AND table_name = ? AND table_comment = ?", db, "tag_term",
                V2_EXPECTED_TABLE_COMMENTS.get("tag_term")))
                .as("词条表注释一致").isEqualTo(1);
        // 每列均带列注释（hifi §3 注释 = 文档化承诺，逐列核对）
        for (final Map.Entry<String, List<String>> expected : V2_EXPECTED_COLUMNS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                            + "AND table_name = ? AND column_comment IS NOT NULL AND column_comment <> ''",
                    db, expected.getKey()))
                    .as("列注释齐备：" + expected.getKey()).isEqualTo(expected.getValue().size());
        }
        // 匹配口径前提：归一化名列排序规则 = 0900_ai_ci（大小写不敏感，hifi §3.2）
        assertThat(collation(db, "tag_term", "normalized_term")).isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1"))
                .as("V2 迁移应在位且成功").isEqualTo(1);
        // V1 零改动承诺：四表仍在位且列齐（本卡不动既有结构）
        assertThat(columnNames(db, "dataset"))
                .as("V1 dataset 列未被 V2 扰动").isEqualTo(EXPECTED_COLUMNS.get("dataset"));
    }

    // ==== 探针 8：V2 三唯一约束在场（uk_vocabulary_code / uk_term_code / uk_vocab_norm_term）====

    @Test
    void tagVocabularyUniqueIndexesPresent() throws SQLException {
        final String db = freshDatabaseWithMigration("vocab_indexes");
        assertThat(indexColumns(db, "tag_vocabulary", "uk_vocabulary_code"))
                .containsExactly("vocabulary_code");
        assertThat(indexColumns(db, "tag_term", "uk_term_code")).containsExactly("term_code");
        assertThat(indexColumns(db, "tag_term", "uk_vocab_norm_term"))
                .containsExactlyInAnyOrder("vocabulary_id", "normalized_term");
    }

    // ==== 探针 9：词表册码重复被拒（反向探针——存储引擎原子层兜底）====

    @Test
    void duplicateVocabularyCodeRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("vocab_dup_code");
        // 已内置 SEMANTIC_TAG 种子册，此处用另一未占用码做重复插入探针
        insertVocabulary(db, "EXT_TAG", "扩展词表册");
        assertThatThrownBy(() -> insertVocabulary(db, "EXT_TAG", "重复词表册二"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        assertThat(count(db, "SELECT COUNT(*) FROM tag_vocabulary")).isEqualTo(2);
        // 种子册本身亦受唯一键保护（再加一册 SEMANTIC_TAG 必被拒）
        assertThatThrownBy(() -> insertVocabulary(db, "SEMANTIC_TAG", "重复语义标签册"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    // ==== 探针 10：词条编号重复 / 同册归一化名重复被拒，跨册同名允许（反向探针）====

    @Test
    void duplicateTermCodeAndSameVocabularyNormalizedNameRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("term_dup");
        final long vocabularyId = semanticVocabularyId(db);
        insertTerm(db, vocabularyId, "TT0099", "新增词条", "新增词条");
        // 同一词条编号重复（跨册亦拒——编号全册唯一）
        assertThatThrownBy(() -> insertTerm(db, vocabularyId, "TT0099", "编号重复词条", "编号重复词条"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 同册归一化名重复（维护动作误插同名词的最后兜底）
        assertThatThrownBy(() -> insertTerm(db, vocabularyId, "TT0098", "同名词条", "新增词条"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 跨册同名允许（词表册可多册，口径 = 同册内唯一）
        final long otherId = insertVocabulary(db, "OTHER_TAG", "另一词表册");
        insertTerm(db, otherId, "TT0097", "同名不同册", "新增词条");
        assertThat(count(db, "SELECT COUNT(*) FROM tag_term WHERE normalized_term = '新增词条'"))
                .isEqualTo(2);
    }

    // ==== 探针 11：种子词条 12 条且含既有演示标签（TT0001~0003 = 金融/普惠/风控）====

    @Test
    void seedTermsCoverExistingWalkthroughTags() throws SQLException {
        final String db = freshDatabaseWithMigration("vocab_seed");
        assertThat(stringList(db, "SELECT term_code FROM tag_term ORDER BY term_code")).as("种子词条 12 条")
                .hasSize(12).contains("TT0001", "TT0002", "TT0003");
        assertThat(stringList(db, "SELECT term_name FROM tag_term WHERE term_code IN "
                + "('TT0001', 'TT0002', 'TT0003') ORDER BY term_code"))
                .as("既有演示/走查标签必须在种子集内（Q4-A）").containsExactly("金融", "普惠", "风控");
        assertThat(stringList(db, "SELECT term_code FROM tag_term WHERE term_name <> normalized_term"))
                .as("种子归一化名与词条名一致").isEmpty();
        assertThat(stringList(db, "SELECT term_code FROM tag_term WHERE term_code NOT REGEXP '^TT[0-9]{4}$'"))
                .as("词条编号形态 TT+4 位").isEmpty();
        assertThat(count(db, "SELECT COUNT(DISTINCT vocabulary_id) FROM tag_term"))
                .as("种子全部归属同一词表册").isEqualTo(1);
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

    /** 种子词表册 id（受控词表代号恒 SEMANTIC_TAG，hifi §3.3）。 */
    private static long semanticVocabularyId(final String database) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM tag_vocabulary WHERE vocabulary_code = 'SEMANTIC_TAG'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertVocabulary(final String database, final String code, final String name)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO tag_vocabulary (vocabulary_code, vocabulary_name) VALUES (?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            bind(statement, new Object[] {code, name});
            statement.executeUpdate();
            try (ResultSet rs = statement.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertTerm(final String database, final long vocabularyId, final String termCode,
            final String termName, final String normalized) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO tag_term (vocabulary_id, term_code, term_name, normalized_term) "
                             + "VALUES (?, ?, ?, ?)")) {
            bind(statement, new Object[] {vocabularyId, termCode, termName, normalized});
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
