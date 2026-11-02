package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.metering.support.SharedMySqlContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@Testcontainers(disabledWithoutDocker = true)
class BillingMigrationStructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_migration_it");
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

    @Test
    void billNoSequenceSeeded() {
        final Long nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM billing_bill_no_seq WHERE id = 1", Long.class);
        assertThat(nextNo).isNotNull().isPositive();
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
