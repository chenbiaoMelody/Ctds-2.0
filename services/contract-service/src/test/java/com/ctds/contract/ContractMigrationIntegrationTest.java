package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.support.SharedMySqlContainer;
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
 * 迁移与种子探针（WBS-3.4.2 hifi §7 T0；行为 1 规则 2"预置三类模板随 3.4.2 交付落库"）：
 * 四表结构齐备；预置三类模板各 1 条 + V1 版本行 + 启用中；条款框架槽位数（公共 7 + 类型差异化）；
 * 序号表初始 next_no = 4（预置占 CT000001~CT000003，运营新增从 CT000004 起）。
 * 真实 MySQL 8 容器实跑 Flyway V1；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractMigrationIntegrationTest {

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_migration_it");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fourTablesExist() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("contract_template", "contract_template_version",
                "contract_template_action_log", "contract_template_no_seq");
    }

    @Test
    void threePresetTemplatesSeededWithV1Enabled() {
        final List<Map<String, Object>> templates = jdbcTemplate.queryForList(
                "SELECT template_no, template_type, current_version, status FROM contract_template "
                        + "ORDER BY id ASC");
        assertThat(templates).hasSize(3);
        assertThat(templates).extracting(t -> t.get("template_no"))
                .containsExactly("CT000001", "CT000002", "CT000003");
        assertThat(templates).extracting(t -> t.get("template_type"))
                .containsExactly("PUBLIC_DATA_AUTHORIZATION", "API_CALL", "PRIVACY_COMPUTING");
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.get("current_version")).isEqualTo(1);
            assertThat(t.get("status")).isEqualTo("ENABLED");
        });
        // 三类模板各带 V1 版本行（种子框架全文随迁移落库——演示前提④兑现）
        final Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_version", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }

    @Test
    void seedFrameworksCarryConfirmedSlotSets() {
        // 槽位集合 = lofi §3 确认清单：公共骨架 7 必填 + 差异化（公共数据授权 2+1 / API 2+1 / 隐私计算 3+1）
        assertThat(slotCount("CT000001")).isEqualTo(10);
        assertThat(slotCount("CT000002")).isEqualTo(10);
        assertThat(slotCount("CT000003")).isEqualTo(11);
    }

    @Test
    void noSeqStartsAtFour() {
        final Integer nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM contract_template_no_seq WHERE id = 1", Integer.class);
        assertThat(nextNo).isEqualTo(4);
    }

    private int slotCount(final String templateNo) {
        final String framework = jdbcTemplate.queryForObject(
                "SELECT v.clause_framework FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id "
                        + "WHERE t.template_no = ? AND v.version_no = 1", String.class, templateNo);
        return framework.split("\\{\"key\"").length - 1;
    }
}
