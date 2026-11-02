package com.ctds.metering.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 计费测试的合约域事件源替身表（列面沿 contract-service 迁移 V2/V3 亲读口径）：
 * contract 需含计费快照全部列（定价档 / 定价数值 / 生效终止时点 / 产品），
 * usage_counter / usage_log 需含对账四方数值所需列。测试替身只含计费与对账所需列。
 */
public final class BillingSourceSchemas {

    private BillingSourceSchemas() {
    }

    /** 合约域三表（contract / contract_usage_log / contract_usage_counter）。 */
    public static void createContractTables(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "contract_no VARCHAR(32) NOT NULL, "
                + "product_id BIGINT NOT NULL DEFAULT 0, "
                + "product_name VARCHAR(128) NOT NULL DEFAULT '', "
                + "provider_subject_no VARCHAR(32) NOT NULL, "
                + "requester_subject_no VARCHAR(32) NOT NULL, "
                + "pricing_model VARCHAR(16) NOT NULL, "
                + "price_amount DECIMAL(12,2) NULL, "
                + "status VARCHAR(32) NOT NULL DEFAULT 'EFFECTIVE', "
                + "effective_at DATETIME NULL, "
                + "ended_at DATETIME NULL, "
                + "UNIQUE KEY uk_contract_no (contract_no))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract_usage_counter ("
                + "contract_no VARCHAR(20) NOT NULL PRIMARY KEY, "
                + "used_count INT NOT NULL DEFAULT 0, "
                + "last_used_at DATETIME NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract_usage_log ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "contract_no VARCHAR(20) NOT NULL, "
                + "requester_no VARCHAR(20) NOT NULL, "
                + "action_type VARCHAR(16) NOT NULL, "
                + "outcome VARCHAR(8) NOT NULL, "
                + "reason_code VARCHAR(16) NULL, "
                + "violations VARCHAR(128) NULL, "
                + "used_count INT NULL, "
                + "occurred_at DATETIME NOT NULL)");
    }
}
