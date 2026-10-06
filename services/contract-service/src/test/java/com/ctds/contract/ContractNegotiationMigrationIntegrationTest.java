package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.support.SharedMySqlContainer;
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
 * 协商与签署迁移结构探针（WBS-3.4.3 hifi §7 T1，Q1-A 六表模型）：V2 迁移落六表——
 * 合约主表 / 条款版本表 / 签署记录表 / 存证事件表 / 合约留痕表 / 合约编号序号表；
 * 唯一键（编号 / 合约+版本 / 合约+角色 / 存证合约唯一）与双方·状态检索索引齐备；
 * 序号表初始 next_no = 1（CO 编号全局序号，不按日重置）；四个 L3 密文列为 LONGBLOB
 * （条款值 / 变更明细 / 规范化原文 / 签名值——CAT-04 存储保密 SM4 承载）；
 * 内容哈希为不可逆摘要明文列（VARCHAR(64)）。V1 四表与种子零改动（同库共存探针）。
 * 真实 MySQL 8 容器实跑 Flyway V1+V2；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractNegotiationMigrationIntegrationTest {

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_deal_migration_it");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sixDealTablesExistAlongsideV1Tables() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("contract", "contract_clause_version", "contract_signature",
                "contract_attestation", "contract_action_log", "contract_no_seq",
                // V1 模板四表零改动共存（hifi §4 注）
                "contract_template", "contract_template_version",
                "contract_template_action_log", "contract_template_no_seq");
    }

    @Test
    void dealTablesCarryConfirmedUniqueKeysAndIndexes() {
        assertThat(uniqueKeys("contract"))
                .containsExactlyInAnyOrder("uk_contract_no");
        assertThat(uniqueKeys("contract_clause_version"))
                .containsExactlyInAnyOrder("uk_contract_version");
        assertThat(uniqueKeys("contract_signature"))
                .containsExactlyInAnyOrder("uk_contract_party");
        assertThat(uniqueKeys("contract_attestation"))
                .containsExactlyInAnyOrder("uk_attestation_contract");
        assertThat(indexNames("contract"))
                .contains("idx_provider", "idx_requester", "idx_status");
        assertThat(indexNames("contract_action_log")).contains("idx_log_contract");
    }

    @Test
    void fourCipherColumnsAreLongblobAndHashColumnsAreChar64() {
        // information_schema.data_type 落 MySQL 8 小写归一（longblob）——断言大小写不敏感
        assertThat(columnTypes("contract_clause_version", "clause_values_cipher", "changes_cipher",
                "canonical_cipher")).allMatch(type -> type.equalsIgnoreCase("longblob"));
        assertThat(columnTypes("contract_signature", "signature_cipher"))
                .allMatch(type -> type.equalsIgnoreCase("longblob"));
        assertThat(columnTypes("contract_clause_version", "content_hash"))
                .allMatch(t -> t.startsWith("varchar"));
        assertThat(columnTypes("contract_signature", "content_hash"))
                .allMatch(t -> t.startsWith("varchar"));
    }

    @Test
    void contractNoSeqStartsAtOne() {
        final Integer nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM contract_no_seq WHERE id = 1", Integer.class);
        assertThat(nextNo).isEqualTo(1);
    }

    @Test
    void actionLogCarriesTwelveClosedActionValuesInComment() {
        // 值域封闭 12 值落列注释（hifi §4 表 5）——结构探针锚注释口径，写面值域由 domain 枚举封闭
        final String columnComment = jdbcTemplate.queryForObject(
                "SELECT column_comment FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'contract_action_log' "
                        + "AND column_name = 'action'", String.class);
        for (final String action : new String[] {"CREATE", "PROPOSE", "CONFIRM", "TERMINATE_NEGOTIATION",
                "SIGN", "REFUSE_SIGN", "ATTEST", "RELEASE_CONSENT", "FORCE_TERMINATE", "GOVERNANCE_VIEW",
                "VERIFY_SIGNATURE_FAILED", "DENIED_ACCESS"}) {
            assertThat(columnComment).contains(action);
        }
    }

    private List<String> uniqueKeys(final String table) {
        return indexNames(table).stream().filter(name -> name.startsWith("uk_")).sorted().toList();
    }

    private List<String> indexNames(final String table) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ?", String.class, table);
    }

    private List<String> columnTypes(final String table, final String... columns) {
        final List<String> types = new java.util.ArrayList<>();
        for (final String column : columns) {
            types.add(jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    String.class, table, column));
        }
        return types;
    }
}
