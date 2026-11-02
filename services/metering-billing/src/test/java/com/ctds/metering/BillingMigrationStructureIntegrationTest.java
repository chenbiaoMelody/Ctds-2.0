package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.metering.domain.billing.BillNoGenerator;
import com.ctds.metering.support.FixedClockTestConfiguration;
import com.ctds.metering.support.SharedMySqlContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 计费迁移结构（hifi §11 TB13）：V2 五表存在、唯一键与生成列语义正确、取号行就绪、
 * 账单表无金额修改路径（结构性保证由"无 UPDATE 金额 SQL"+ 唯一键承载）。Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.billing.auto.enabled=false"})
@ActiveProfiles("mysql")
@Import(FixedClockTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingMigrationStructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BillNoGenerator billNoGenerator;

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_migration_it");
    }

    @BeforeEach
    void clean() {
        // 本类会直插固定账单号，清理保证同一容器内可重复执行（不触碰 seed 序号）
        jdbcTemplate.update("DELETE FROM billing_bill_detail");
        jdbcTemplate.update("DELETE FROM billing_bill_action_log");
        jdbcTemplate.update("DELETE FROM billing_bill");
    }

    @Test
    void billingTablesCreated() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).contains("billing_bill", "billing_bill_detail",
                "billing_bill_action_log", "billing_reconciliation", "billing_bill_no_seq");
    }

    @Test
    void uniqueKeysAndGeneratedColumnEnforced() {
        final List<String> billIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'billing_bill'",
                String.class);
        assertThat(billIndexes).contains("uk_bill_no", "uk_active_normal_key",
                "idx_contract_period", "idx_requester", "idx_provider", "idx_product", "idx_status");
        final String extra = jdbcTemplate.queryForObject(
                "SELECT extra FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'billing_bill' AND column_name = 'active_normal_key'",
                String.class);
        assertThat(extra).as("生成列 STORED").containsIgnoringCase("STORED");

        insertBill("BL900001", "NORMAL", "CO940001", "2026-10");
        assertThat(jdbcTemplate.queryForObject("SELECT active_normal_key FROM billing_bill "
                + "WHERE bill_no = 'BL900001'", String.class)).isEqualTo("CO940001#2026-10");
        assertThatThrownBy(() -> insertBill("BL900002", "NORMAL", "CO940001", "2026-10"))
                .as("同合约同周期普通账单唯一").isInstanceOf(DuplicateKeyException.class);

        insertBill("BL900003", "CORRECTION", "CO940001", "2026-10");
        insertBill("BL900004", "CORRECTION", "CO940001", "2026-10");
        final Long corrections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill WHERE bill_type = 'CORRECTION'", Long.class);
        assertThat(corrections).as("更正单可多份（生成列为 NULL）").isEqualTo(2L);
    }

    /** 读面与对账索引锚（hifi §2 索引清单；TB13）。 */
    @Test
    void readAndReconcileIndexesPresent() {
        final List<String> detailIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'billing_bill_detail'",
                String.class);
        assertThat(detailIndexes).contains("uk_bill_line", "idx_bill_no");
        final List<String> reconIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'billing_reconciliation'",
                String.class);
        assertThat(reconIndexes).contains("idx_recon_contract_period");
    }

    /**
     * 取号行就绪：迁移种子号 = 1（= 已用最大序号），首个账单号 = BL000002，取号严格自增且不复用（B10-2）。
     */
    @Test
    void billNoSequenceSeeded() {
        final Long seed = jdbcTemplate.queryForObject(
                "SELECT next_no FROM billing_bill_no_seq WHERE id = 1", Long.class);
        assertThat(seed).as("迁移种子号 (1,1)").isEqualTo(1L);

        assertThat(billNoGenerator.nextBillNo()).as("首个账单号 = 种子 + 1").isEqualTo("BL000002");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_no FROM billing_bill_no_seq WHERE id = 1", Long.class))
                .as("取号后序号自增").isEqualTo(2L);
        assertThat(billNoGenerator.nextBillNo()).as("序号不复用").isEqualTo("BL000003");

        // 还原种子，避免与本类其他用例顺序耦合
        jdbcTemplate.update("UPDATE billing_bill_no_seq SET next_no = 1 WHERE id = 1");
    }

    /**
     * 不可变结构性锚（hifi §2 / TB13；评审修复 P2-12）：
     * 计费主源只允许 3 条状态流转 UPDATE（确认 / 冻结 / 解冻）+ 1 条取号 UPDATE，
     * 且不存在任何金额 / 明细字段的 UPDATE 语句——新增修改路径即红。
     */
    @Test
    void sourceHasNoAmountOrDetailUpdateSql() throws IOException {
        final Path mainSources = Path.of("src", "main", "java");
        assertThat(mainSources).as("以模块目录为工作目录运行测试").exists();

        int statusUpdates = 0;
        final List<String> amountUpdates = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                final String source = read(file);
                statusUpdates += countOccurrences(source, "UPDATE billing_bill SET");
                if (source.contains("UPDATE billing_bill_detail")
                        || source.contains("SET amount =")
                        || source.contains("SET quantity =")
                        || source.contains("SET unit_price =")
                        || source.contains("SET base_amount =")
                        || source.contains("SET share_rate =")) {
                    amountUpdates.add(file.toString());
                }
            }
        }
        assertThat(amountUpdates).as("账单金额 / 明细不存在修改路径（B10-3）").isEmpty();
        assertThat(statusUpdates)
                .as("状态流转 UPDATE 恰 3 条（确认 / 冻结 / 解冻），新增即为不可变破口")
                .isEqualTo(3);
        // 取号 UPDATE 单独锚定（billing_bill_no_seq，非账单表）
        assertThat(countOccurrences(read(Path.of("src", "main", "java", "com", "ctds", "metering",
                "infrastructure", "billing", "JdbcBillNoGenerator.java")),
                "UPDATE billing_bill_no_seq SET")).isEqualTo(1);
    }

    private static int countOccurrences(final String text, final String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void insertBill(final String billNo, final String billType, final String contractNo,
            final String period) {
        jdbcTemplate.update("INSERT INTO billing_bill (bill_no, bill_type, contract_no, requester_no, "
                        + "provider_no, product_id, product_name, `period`, pricing_model, amount, "
                        + "status, created_by, created_at) VALUES (?, ?, ?, 'S-req', 'S-prov', 101, "
                        + "'测试数据集', ?, 'MONTHLY', 10.00, 'ISSUED', 'tester', NOW())",
                billNo, billType, contractNo, period);
    }
}
