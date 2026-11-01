package com.ctds.metering.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 跨 schema 采集测试基座：同一容器实例上准备事件源库（列名/类型沿上游迁移亲读——
 * contract V1/V2/V3 与 delivery V1/V2/V3 的采集所需列面，hifi §2 查询契约依据），
 * 供通道 2 只读 SELECT 与四方勾稽读数使用。测试替身表只含采集/勾稽所需列。
 */
public final class SourceSchemas {

    private SourceSchemas() {
    }

    /** 准备库（建库 + 应用用户授权，沿 SharedMySqlContainer.prepare 同款）并返回 JDBC URL。 */
    public static JdbcTemplate jdbcTemplateFor(final String database) {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                SharedMySqlContainer.jdbcUrlFor(database),
                SharedMySqlContainer.container().getUsername(),
                SharedMySqlContainer.container().getPassword());
        return new JdbcTemplate(dataSource);
    }

    /** 交付域事件源表（delivery / sandbox_job / sandbox_task——V1/V2 迁移采集所需列面）。 */
    public static void createDeliveryTables(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS delivery ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "delivery_no VARCHAR(16) NOT NULL, "
                + "contract_no VARCHAR(32) NOT NULL, "
                + "requester_no VARCHAR(32) NOT NULL, "
                + "delivery_type VARCHAR(16) NOT NULL, "
                + "result VARCHAR(16) NOT NULL, "
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_delivery_no (delivery_no))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sandbox_task ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "sandbox_no VARCHAR(16) NOT NULL, "
                + "contract_no VARCHAR(32) NOT NULL, "
                + "requester_no VARCHAR(32) NOT NULL, "
                + "UNIQUE KEY uk_sandbox_no (sandbox_no))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sandbox_job ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "job_no VARCHAR(16) NOT NULL, "
                + "sandbox_no VARCHAR(16) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, "
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_sandbox_job_no (job_no))");
    }

    /** 合约域事件源表（contract / contract_usage_log / contract_usage_counter——V2/V3 采集所需列面）。 */
    public static void createContractTables(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "contract_no VARCHAR(32) NOT NULL, "
                + "requester_subject_no VARCHAR(32) NOT NULL, "
                + "provider_subject_no VARCHAR(32) NOT NULL, "
                + "pricing_model VARCHAR(16) NOT NULL, "
                + "UNIQUE KEY uk_contract_no (contract_no))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract_usage_log ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "contract_no VARCHAR(20) NOT NULL, "
                + "outcome VARCHAR(8) NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS contract_usage_counter ("
                + "contract_no VARCHAR(20) NOT NULL PRIMARY KEY, "
                + "used_count INT NOT NULL DEFAULT 0)");
    }
}
