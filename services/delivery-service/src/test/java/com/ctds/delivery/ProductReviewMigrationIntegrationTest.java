package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.delivery.support.SharedMySqlContainer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 评价域迁移结构锚（WBS-3.7.4 hifi §8 T9；沿 3.7.3 T12 同型）：
 * V4 两表齐备（product_review + product_review_action_log）；uk_review_once 一人一评唯一键、
 * idx_product_visible 统计/列表索引、留痕表 idx_review；hidden 默认 0。
 * 真实 MySQL 8 容器实跑 Flyway V1~V4；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ProductReviewMigrationIntegrationTest {

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_review_migration_it");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reviewTablesExist() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("product_review", "product_review_action_log");
    }

    @Test
    void productReviewColumnsMatchContract() {
        final List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_review' "
                        + "ORDER BY ordinal_position");
        final List<String> names = columns.stream()
                .map(c -> String.valueOf(c.get("column_name"))).toList();
        assertThat(names).contains("id", "product_id", "requester_no", "score", "comment",
                "hidden", "hidden_by", "hidden_at", "created_at", "updated_at");
        // hidden 默认 0（有效态；隐藏由运营动作置位）
        final Map<String, Object> hidden = columns.stream()
                .filter(c -> "hidden".equals(String.valueOf(c.get("column_name"))))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(hidden.get("column_default"))).isEqualTo("0");
        // 一人一评字段非空（uk 兜底前提）
        final List<String> notNull = columns.stream()
                .filter(c -> "NO".equals(String.valueOf(c.get("is_nullable"))))
                .map(c -> String.valueOf(c.get("column_name"))).toList();
        assertThat(notNull).contains("product_id", "requester_no", "score", "hidden");
    }

    @Test
    void uniqueAndVisibleIndexesExist() {
        final List<String> reviewIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_review'",
                String.class);
        assertThat(reviewIndexes).contains("PRIMARY", "uk_review_once", "idx_product_visible");
        final List<String> logIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_review_action_log'",
                String.class);
        assertThat(logIndexes).contains("PRIMARY", "idx_review");
        // uk_review_once = (product_id, requester_no) 复合序（一人一评语义锚）
        final List<String> ukColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_review' "
                        + "AND index_name = 'uk_review_once' ORDER BY seq_in_index", String.class);
        assertThat(ukColumns).containsExactly("product_id", "requester_no");
        // uk_review_once 必须真唯一（non_unique=0；防误写普通 KEY 断言仍绿——评审 P2-J）
        final Integer nonUnique = jdbcTemplate.queryForObject(
                "SELECT MAX(non_unique) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_review' "
                        + "AND index_name = 'uk_review_once'", Integer.class);
        assertThat(nonUnique).isZero();
    }
}
