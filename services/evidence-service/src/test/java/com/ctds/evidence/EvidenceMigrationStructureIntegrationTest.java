package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.evidence.support.SharedMySqlContainer;
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
 * 迁移结构锚（hifi §2 DDL 契约）：V1 四表 + V2 审计三表落库、列面逐字对齐（零原文 / 个人信息 / 密钥材料列）、
 * uk_evidence_idem 三元组幂等锚 + uk_chain_payload 重放幂等锚 + uk_report_no 报告编号唯一锚（非唯一索引口径逐项断言）。
 * 真实 MySQL 8 容器实跑 Flyway V1~V2；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class EvidenceMigrationStructureIntegrationTest {

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_structure_it");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fourTablesExist() {
        final List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("evidence_no_seq", "evidence_record",
                "evidence_record_action_log", "evidence_chain_block");
    }

    /** WBS-3.8.4 T21 / hifi §2：V2 审计三表落库（留痕 / 报告登记 / 报告取号）。 */
    @Test
    void auditTablesExist() {
        final List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("evidence_access_log", "evidence_audit_report",
                "evidence_report_no_seq");
    }

    /** WBS-3.8.4 T21 / hifi §2：evidence_access_log 列面逐字（脱敏键值留痕，零原文 / 个人信息列）。 */
    @Test
    void accessLogColumnsMatchContract() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'evidence_access_log' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "access_kind", "accessed_by", "conditions_json",
                "result_count", "purpose", "created_at");
    }

    /** WBS-3.8.4 T21 / hifi §2：evidence_audit_report 列面逐字（内容快照 + SM3 摘要 + 唯一编号）。 */
    @Test
    void auditReportColumnsMatchContract() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'evidence_audit_report' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "report_no", "scope_json", "record_count",
                "conclusion_summary_json", "anomaly_count", "report_content_json", "content_digest",
                "remark", "generated_by", "generated_at");
    }

    /** WBS-3.8.4 T21 / hifi §2：报告编号唯一锚（uk_report_no）为唯一索引。 */
    @Test
    void auditReportNoAnchorIsUniqueIndex() {
        final List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT index_name, non_unique, column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND index_name = 'uk_report_no' "
                        + "ORDER BY seq_in_index");
        assertThat(indexes).extracting(i -> i.get("index_name") + ":" + i.get("column_name"))
                .containsExactly("uk_report_no:report_no");
        assertThat(indexes).allSatisfy(i ->
                assertThat(((Number) i.get("non_unique")).intValue()).as("唯一键").isZero());
    }

    @Test
    void recordColumnsMatchContract() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'evidence_record' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "evidence_no", "stage_type", "business_no",
                "operation_type", "subject_did_digest", "occurred_at", "content_digest", "status",
                "chain_tx_id", "chain_block_no", "retry_count", "last_error_summary",
                "created_at", "updated_at");
    }

    @Test
    void chainBlockColumnsMatchContract() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'evidence_chain_block' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("block_no", "chain_tx_id", "payload_digest",
                "payload_json", "prev_block_hash", "block_hash", "created_at");
    }

    @Test
    void uniqueAnchorsAreUniqueIndexes() {
        final List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT index_name, non_unique, column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND index_name IN "
                        + "('uk_evidence_idem', 'uk_evidence_no', 'uk_chain_payload', 'uk_chain_tx') "
                        + "ORDER BY index_name, seq_in_index");
        assertThat(indexes).extracting(i -> i.get("index_name") + ":" + i.get("column_name"))
                .containsExactly(
                        "uk_chain_payload:payload_digest",
                        "uk_chain_tx:chain_tx_id",
                        "uk_evidence_idem:stage_type",
                        "uk_evidence_idem:business_no",
                        "uk_evidence_idem:operation_type",
                        "uk_evidence_no:evidence_no");
        assertThat(indexes).allSatisfy(i ->
                assertThat(((Number) i.get("non_unique")).intValue()).as("唯一键").isZero());
    }
}
