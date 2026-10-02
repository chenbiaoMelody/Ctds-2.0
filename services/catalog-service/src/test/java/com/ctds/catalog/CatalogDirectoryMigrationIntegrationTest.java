package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.catalog.domain.DatasetNameNormalizer;
import com.ctds.catalog.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 统一目录服务库表迁移与约束探针（WBS-3.3.4 hifi §3 + §5 T1；任务卡关闭条件②的库侧承诺）。
 *
 * <p>真实 MySQL 8 容器实跑 V1+V2+V3 全新迁移（沿 {@link CatalogMigrationIntegrationTest} 先例：
 * 每用例独立库名，隔离语义等价方法级容器，ADR-010 §3.6"独立 schema"口径）。四条唯一性硬约束的
 * 反向探针：规格规则不允许只有应用层判定，DB 兜底的失败路径必须有测试（uk_category_code /
 * uk_provider_product_name / product_favorite 与 product_subscription 的 uk_subject_product）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogDirectoryMigrationIntegrationTest {

    /** 独立库名计数器（每用例一库，命名受控）。 */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    /** WBS-3.3.4 hifi §3 V3 六表列清单（键 = 表名；值 = 按 ordinal_position 的列序）。 */
    private static final Map<String, List<String>> V3_EXPECTED_COLUMNS = buildV3ExpectedColumns();

    /** WBS-3.3.4 hifi §3 V3 六表注释（以 V3 迁移 SQL 注释为准的文档化承诺，逐表核对）。 */
    private static final Map<String, String> V3_EXPECTED_TABLE_COMMENTS = buildV3ExpectedTableComments();

    private static Map<String, List<String>> buildV3ExpectedColumns() {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("category_node", List.of("id", "category_code", "category_name", "normalized_name",
                "parent_code", "sort_order", "created_at"));
        columns.put("data_product", List.of("id", "product_name", "normalized_product_name", "intro",
                "product_type", "pricing_model", "price_amount", "status", "provider_subject_no",
                "dataset_id", "category_code", "listed_at", "created_at", "updated_at"));
        columns.put("product_favorite", List.of("id", "subject_no", "product_id", "created_at"));
        columns.put("product_subscription", List.of("id", "subject_no", "product_id", "created_at"));
        columns.put("product_interaction_log", List.of("id", "subject_no", "product_id", "action",
                "outcome", "deny_reason", "created_at"));
        columns.put("product_action_log", List.of("id", "product_id", "action", "operator_subject_no",
                "summary", "created_at"));
        return columns;
    }

    private static Map<String, String> buildV3ExpectedTableComments() {
        final Map<String, String> comments = new LinkedHashMap<>();
        comments.put("category_node",
                "平台受控类目树（2级；种子随迁移内置；类目成员校验比对面；与语义标签词表两套受控集合物理分离）");
        comments.put("data_product",
                "数据产品最小载体表（一行=一个产品；目录检索对象；封装/上下架/注销写面归3.3.5，本表由其迁移增列扩展）");
        comments.put("product_favorite",
                "产品收藏关系表（一行=一主体收藏一产品；幂等=唯一键兜底+重放首次结果；下架/注销后条目保留不删，状态读时计算）");
        comments.put("product_subscription",
                "产品订阅关系表（一行=一主体订阅一产品；变更感知=产品变更留痕可查；条目保留不删，状态读时计算）");
        comments.put("product_interaction_log",
                "目录域收藏订阅动作留痕（收藏/取消/订阅/退订+DENIED拒绝留痕；四要素：谁/何时/哪个产品/动作+结果；不含敏感原文；只插不改）");
        comments.put("product_action_log",
                "产品变更留痕表（载体随3.3.4建、写入动作码集合随3.3.5登记；订阅感知读面对本表只读；只插不改）");
        return comments;
    }

    // ==== 探针 1：V3 迁移冒烟——六表齐备、列齐（含顺序）、表与列注释、迁移历史、V1/V2 零改动对照 ====

    @Test
    void directoryMigrationCreatesSixTablesWithExpectedShape() throws SQLException {
        final String db = freshDatabaseWithMigration("dir_shape");
        for (final Map.Entry<String, List<String>> expected : V3_EXPECTED_COLUMNS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name = ?", db, expected.getKey()))
                    .as("表应存在：" + expected.getKey()).isEqualTo(1);
            assertThat(columnNames(db, expected.getKey())).as("列齐且顺序一致：" + expected.getKey())
                    .isEqualTo(expected.getValue());
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                            + "AND table_name = ? AND column_comment IS NOT NULL AND column_comment <> ''",
                    db, expected.getKey()))
                    .as("列注释齐备：" + expected.getKey()).isEqualTo(expected.getValue().size());
        }
        for (final Map.Entry<String, String> expected : V3_EXPECTED_TABLE_COMMENTS.entrySet()) {
            assertThat(count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                            + "AND table_name = ? AND table_comment = ?", db, expected.getKey(),
                    expected.getValue()))
                    .as("表注释一致：" + expected.getKey()).isEqualTo(1);
        }
        assertThat(count(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 1"))
                .as("V3 迁移应在位且成功").isEqualTo(1);
        // V1/V2 零改动承诺：既有六表仍在位且列齐（本卡不动既有结构）
        assertThat(columnNames(db, "dataset"))
                .as("V1 dataset 列未被 V3 扰动").containsExactly("id", "data_no", "space_id",
                        "owner_subject_no", "name", "normalized_name", "type", "intro", "semantic_tags",
                        "declare_category", "declare_level", "declare_important", "status", "created_at",
                        "updated_at");
        assertThat(columnNames(db, "tag_term"))
                .as("V2 tag_term 列未被 V3 扰动").containsExactly("id", "vocabulary_id", "term_code",
                        "term_name", "normalized_term", "created_at");
    }

    // ==== 探针 2：目录域索引在场（uk ×3 处 + 业务索引；归一化名列排序规则前提）====

    @Test
    void directoryIndexesPresent() throws SQLException {
        final String db = freshDatabaseWithMigration("dir_indexes");
        assertThat(indexColumns(db, "category_node", "uk_category_code")).containsExactly("category_code");
        assertThat(indexColumns(db, "data_product", "uk_provider_product_name"))
                .containsExactlyInAnyOrder("provider_subject_no", "product_name");
        assertThat(indexColumns(db, "data_product", "idx_status_category"))
                .containsExactlyInAnyOrder("status", "category_code");
        assertThat(indexColumns(db, "data_product", "idx_listed_at")).containsExactly("listed_at");
        assertThat(indexColumns(db, "product_favorite", "uk_subject_product"))
                .containsExactlyInAnyOrder("subject_no", "product_id");
        assertThat(indexColumns(db, "product_favorite", "idx_product_id")).containsExactly("product_id");
        assertThat(indexColumns(db, "product_subscription", "uk_subject_product"))
                .containsExactlyInAnyOrder("subject_no", "product_id");
        assertThat(indexColumns(db, "product_subscription", "idx_product_id")).containsExactly("product_id");
        assertThat(indexColumns(db, "product_interaction_log", "idx_subject_created"))
                .containsExactlyInAnyOrder("subject_no", "created_at");
        assertThat(indexColumns(db, "product_action_log", "idx_product_created"))
                .containsExactlyInAnyOrder("product_id", "created_at");
        // 匹配口径前提：类目归一化名列排序规则 = 0900_ai_ci（大小写/重音不敏感，沿词表同款）
        assertThat(collation(db, "category_node", "normalized_name")).isEqualTo("utf8mb4_0900_ai_ci");
    }

    // ==== 探针 3：同一提供方产品名唯一（反向探针——uk_provider_product_name；跨提供方同名允许）====

    @Test
    void sameProviderProductNameDuplicateRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("prod_dup");
        insertProduct(db, "P1", "交通流量预测产品", "LISTED");
        assertThatThrownBy(() -> insertProduct(db, "P1", "交通流量预测产品", "DRAFT"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 跨提供方同名允许（口径 = 同一提供方内唯一，规格行为 3 规则 5）
        insertProduct(db, "P2", "交通流量预测产品", "LISTED");
        assertThat(count(db, "SELECT COUNT(*) FROM data_product WHERE product_name = '交通流量预测产品'"))
                .isEqualTo(2);
    }

    // ==== 探针 4：同主体同产品收藏/订阅各唯一（反向探针——两表 uk_subject_product；幂等兜底）====

    @Test
    void sameSubjectProductDuplicateFavoriteAndSubscriptionRejected() throws SQLException {
        final String db = freshDatabaseWithMigration("fav_dup");
        final long productId = insertProduct(db, "P1", "重复收藏探针产品", "LISTED");
        insertFavorite(db, "S9", productId);
        assertThatThrownBy(() -> insertFavorite(db, "S9", productId))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 同主体不同产品允许（口径 = 同主体同产品）
        final long otherProductId = insertProduct(db, "P1", "另一产品", "LISTED");
        insertFavorite(db, "S9", otherProductId);
        assertThat(count(db, "SELECT COUNT(*) FROM product_favorite")).isEqualTo(2);
        // 订阅同款
        insertSubscription(db, "S9", productId);
        assertThatThrownBy(() -> insertSubscription(db, "S9", productId))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 收藏与订阅互不约束（独立关系，各自唯一）
        assertThat(count(db, "SELECT COUNT(*) FROM product_subscription")).isEqualTo(1);
    }

    // ==== 探针 5：枚举值域封闭以列注释登记（status/product_type/pricing_model 逐值锚定）====

    @Test
    void productEnumValueDomainsAnchoredInColumnComments() throws SQLException {
        final String db = freshDatabaseWithMigration("prod_enum");
        assertThat(columnComment(db, "data_product", "status"))
                .as("状态机四态封闭（hifi §3：枚举封闭，测试探针锚定）")
                .contains("DRAFT", "LISTED", "DELISTED", "CANCELLED");
        assertThat(columnComment(db, "data_product", "product_type"))
                .as("产品形态四类（沿资源四类同款）").contains("API", "DATASET", "REPORT", "MODEL");
        assertThat(columnComment(db, "data_product", "pricing_model"))
                .as("定价模型四档（规格行为 3 规则 3）").contains("FREE", "PER_CALL", "MONTHLY",
                        "REVENUE_SHARE");
        assertThat(columnComment(db, "product_interaction_log", "action"))
                .as("交互留痕动作四值").contains("FAVORITE", "UNFAVORITE", "SUBSCRIBE", "UNSUBSCRIBE");
        assertThat(columnComment(db, "product_interaction_log", "outcome"))
                .as("交互留痕结果两值").contains("SUCCEEDED", "DENIED");
    }

    // ==== 探针 6：种子类目 24 条（8 一级 + 16 二级）且含必含值"金融"（盘点义务①回填锚）====

    @Test
    void seedCategoriesTwentyFourWithMandatoryFinanceValue() throws SQLException {
        final String db = freshDatabaseWithMigration("cat_seed");
        final List<String> codes = stringList(db, "SELECT category_code FROM category_node ORDER BY id");
        assertThat(codes).as("种子类目 24 条（Q3-A 定稿）").hasSize(24);
        assertThat(count(db, "SELECT COUNT(*) FROM category_node WHERE parent_code IS NULL"))
                .as("一级 8 个").isEqualTo(8);
        assertThat(count(db, "SELECT COUNT(*) FROM category_node WHERE parent_code IS NOT NULL"))
                .as("二级 16 个").isEqualTo(16);
        // 必含值（盘点义务①：走查/测试/演示库实际申报值实测 = "金融"，已被一级类目覆盖）
        assertThat(stringList(db, "SELECT category_code FROM category_node WHERE category_name = '金融'"))
                .as("既有演示/走查/测试分类申报值必须在种子集内（Q3-A）").containsExactly("finance");
        // 归一化名 = 应用侧 DatasetNameNormalizer 产出（成员校验比对面，沿词表同款口径）
        for (final Map.Entry<String, String> row : codeNamePairs(db).entrySet()) {
            assertThat(row.getValue())
                    .as("种子归一化名一致：" + row.getKey())
                    .isEqualTo(DatasetNameNormalizer.normalize(row.getValue()));
        }
        assertThat(codes).as("类目码形态 = 语义码小写连字符（ADR-005 命名）")
                .allMatch(code -> code.matches("[a-z][a-z0-9-]*"));
    }

    // ==== 探针 7：种子树形完好——一级 parent 为 NULL、二级 parent 指向既有一级、同级 sort_order 升序 ====

    @Test
    void seedCategoryTreeTwoLevelShapeIntact() throws SQLException {
        final String db = freshDatabaseWithMigration("cat_tree");
        assertThat(count(db, "SELECT COUNT(*) FROM category_node c WHERE c.parent_code IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM category_node p WHERE p.category_code = c.parent_code "
                + "AND p.parent_code IS NULL)")).as("二级 parent 必须指向既有一级").isZero();
        assertThat(count(db, "SELECT COUNT(*) FROM category_node WHERE parent_code IS NULL "
                + "AND sort_order BETWEEN 1 AND 8")).as("一级同级 sort_order 连续").isEqualTo(8);
        assertThat(count(db, "SELECT COUNT(*) FROM (SELECT child.parent_code FROM category_node child "
                + "JOIN category_node parent ON child.parent_code = parent.category_code "
                + "GROUP BY child.parent_code HAVING COUNT(*) <> 2) offenders"))
                .as("每个一级下恰有 2 个二级").isZero();
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

    private static String columnComment(final String database, final String table, final String column)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_comment FROM information_schema.columns WHERE table_schema = ? "
                             + "AND table_name = ? AND column_name = ?")) {
            bind(statement, new Object[] {database, table, column});
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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

    private static Map<String, String> codeNamePairs(final String database) throws SQLException {
        final Map<String, String> pairs = new LinkedHashMap<>();
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT category_code, normalized_name FROM category_node ORDER BY id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                pairs.put(rs.getString(1), rs.getString(2));
            }
        }
        return pairs;
    }

    private static void bind(final PreparedStatement statement, final Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /** 产品行（探针直插；product_type/pricing_model/category_code/dataset_id 取探针固定值）。 */
    private static long insertProduct(final String database, final String providerSubjectNo,
            final String productName, final String status) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                             + "provider_subject_no, dataset_id, category_code, listed_at) "
                             + "VALUES (?, '探针简介', 'DATASET', 'FREE', ?, ?, 1, 'transport', ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            bind(statement, new Object[] {productName, status, providerSubjectNo,
                    "LISTED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null});
            statement.executeUpdate();
            try (ResultSet rs = statement.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertFavorite(final String database, final String subjectNo, final long productId)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO product_favorite (subject_no, product_id) VALUES (?, ?)")) {
            bind(statement, new Object[] {subjectNo, productId});
            statement.executeUpdate();
        }
    }

    private static void insertSubscription(final String database, final String subjectNo, final long productId)
            throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO product_subscription (subject_no, product_id) VALUES (?, ?)")) {
            bind(statement, new Object[] {subjectNo, productId});
            statement.executeUpdate();
        }
    }
}
