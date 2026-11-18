package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.catalog.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V5 产品语义向量缓存表迁移探针（WBS-3.10.2 hifi §7 T8）：真实 MySQL 8 全新迁移 V1~V5；
 * 表/列/注释/主键齐备，V1~V4 既有表结构零扰动。
 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductVectorMigrationIntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private static final String TABLE_COMMENT = "产品语义向量缓存表（WBS-3.10.2；查询时惰性重算+回写；"
            + "下架/注销行保留，检索按 data_product.status LISTED 硬过滤）";

    @Test
    void migrationCreatesProductEmbeddingTableWithoutTouchingExistingTables() throws SQLException {
        final String database = "semantic_migration_" + DB_SEQ.incrementAndGet();
        final String url = SharedMySqlContainer.jdbcUrlFor(database);
        final String username = SharedMySqlContainer.container().getUsername();
        final String password = SharedMySqlContainer.container().getPassword();
        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(columnNames(connection, "product_embedding"))
                    .containsExactly("product_id", "source_hash", "dims", "vector", "updated_at");
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'product_embedding' "
                    + "AND table_comment = ?", TABLE_COMMENT)).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'product_embedding' "
                    + "AND column_comment IS NOT NULL AND column_comment <> ''")).isEqualTo(5);
            assertThat(primaryKeyColumns(connection, "product_embedding"))
                    .containsExactly("product_id");
            assertThat(columnTypes(connection, "product_embedding"))
                    .containsExactly("bigint", "char(64)", "smallint unsigned", "blob", "datetime");
            assertThat(count(connection, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = '5' AND success = 1")).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version IN ('1', '2', '3', '4') AND success = 1")).isEqualTo(4);
            assertThat(columnNames(connection, "data_product"))
                    .as("V1~V4 既有产品表列零扰动").containsExactly("id", "product_name",
                            "normalized_product_name", "intro", "product_type", "pricing_model",
                            "price_amount", "status", "provider_subject_no", "dataset_id",
                            "category_code", "listed_at", "created_at", "updated_at");
            assertThat(columnNames(connection, "dataset")).as("V1 既有资源表列零扰动")
                    .contains("semantic_tags", "normalized_name");
        }
    }

    private static List<String> columnNames(final Connection connection, final String table)
            throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private static List<String> columnTypes(final Connection connection, final String table)
            throws SQLException {
        final List<String> types = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    types.add(resultSet.getString(1));
                }
            }
        }
        return types;
    }

    private static List<String> primaryKeyColumns(final Connection connection, final String table)
            throws SQLException {
        final List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name FROM information_schema.key_column_usage "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND constraint_name = 'PRIMARY' ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private static long count(final Connection connection, final String sql, final Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
