package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.catalog.support.SharedMySqlContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V4 迁移探针（WBS-3.3.5 hifi §5 T6）：data_product 增列与唯一键存在锚、动作码值域注释登记锚
 * （product_action_log/dataset_action_log——沿 DB-29 V4"仅列注释值域登记"先例）、既有列与既有表
 * 零改动锚（V1/V2/V3 结构哈希同族口径的轻量探针）。真实 MySQL 8 容器实跑 Flyway V1~V4；
 * 无 Docker 整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductV4MigrationIntegrationTest {

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_v4_it");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v4ColumnsAndUniqueKeyExistOnDataProduct() {
        final List<String> columns = columnNames("data_product");
        assertThat(columns).as("V4 增列（Q2-A 定价数值 + 归一化产品名）")
                .contains("price_amount", "normalized_product_name");
        final List<String> indexes = jdbc.queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_product' "
                        + "GROUP BY INDEX_NAME", String.class);
        assertThat(indexes).as("归一化唯一键（W8 判重口径唯一来源）+ 既有键保留")
                .contains("uk_provider_norm_name", "uk_provider_product_name");
        final String priceType = jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'data_product' AND COLUMN_NAME = 'price_amount'",
                String.class);
        assertThat(priceType).as("Q2-A 单列 DECIMAL(12,2) NULL").isEqualTo("decimal");
    }

    @Test
    void v4ActionCommentRegistryAnchored() {
        final String productAction = columnComment("product_action_log", "action");
        assertThat(productAction)
                .as("产品侧动作码值域登记（WBS-3.3.5 沿 3.2.3 V2 先例）")
                .contains("CREATE").contains("FORCE_DELIST").contains("GOVERNANCE_VIEW")
                .contains("R8");
        final String datasetAction = columnComment("dataset_action_log", "action");
        assertThat(datasetAction)
                .as("资源侧动作码追加登记（GOVERNANCE_VIEW——Q5-A 资源侧治理例外）")
                .contains("GOVERNANCE_VIEW");
    }

    @Test
    void v1ToV3StructuresUntouched() {
        final List<String> productColumns = columnNames("data_product");
        assertThat(productColumns).as("V3 既有列零改动（Q1-A 授权仅增列）")
                .contains("id", "product_name", "intro", "product_type", "pricing_model", "status",
                        "provider_subject_no", "dataset_id", "category_code", "listed_at",
                        "created_at", "updated_at");
        final List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('dataset', 'dataset_name_lock', 'dataset_action_log', "
                        + "'tag_vocabulary', 'tag_term', 'category_node', 'product_favorite', "
                        + "'product_subscription', 'product_interaction_log')", String.class);
        assertThat(tables).as("V1/V2/V3 九表全部健在").hasSize(9);
    }

    // ==== 助手 ====

    private List<String> columnNames(final String table) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'", String.class);
    }

    private String columnComment(final String table, final String column) {
        return jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
    }
}
