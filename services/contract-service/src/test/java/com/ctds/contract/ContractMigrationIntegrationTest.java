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
 * 真实 MySQL 8 容器实跑 Flyway V1~V4（V4 三订单表结构锚——WBS-3.7.3 T12）；
 * 本机 Docker 未运行时整类跳过（门禁不红）。
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

    /**
     * V4 订单三表结构锚（WBS-3.7.3 hifi §2/§6.1 T12）：三表齐备；active_order_key 为
     * STORED 生成列（仅 IN_PROGRESS 行生成 需方#产品 键——B3-2）；order_no / contract_no /
     * active_order_key 三唯一索引；序号表种子 next_no = 1。
     */
    @Test
    void orderTablesExistWithGeneratedUniqueKey() {
        // 三表落库
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).contains("product_order", "product_order_action_log",
                "product_order_no_seq");
        // active_order_key 生成列（EXTRA 含 STORED GENERATED；表达式按 status 分支生成 需方#产品 键
        // ——MySQL 8 information_schema 无 is_generated 列，生成属性经 extra 列断言）
        final Map<String, Object> generated = jdbcTemplate.queryForMap(
                "SELECT extra, generation_expression FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_order' "
                        + "AND column_name = 'active_order_key'");
        assertThat(String.valueOf(generated.get("extra"))).contains("STORED GENERATED");
        // MySQL 规范化存储含字符集引入与转义引号（_utf8mb4\'#\' 形态），以语义子串锚定
        assertThat(String.valueOf(generated.get("generation_expression")))
                .contains("IN_PROGRESS").contains("#").contains("concat");
        // 三唯一索引：订单号 / 关联合约号 / 进行中唯一键
        final List<String> uniqueIndexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'product_order' "
                        + "AND non_unique = 0 ORDER BY index_name", String.class);
        assertThat(uniqueIndexes).contains("uk_order_no", "uk_contract_no",
                "uk_active_order_key");
        // 订单序号表种子（OD+6 位从 1 起，全局递增）
        final Integer nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM product_order_no_seq WHERE id = 1", Integer.class);
        assertThat(nextNo).isEqualTo(1);
    }

    private int slotCount(final String templateNo) {
        final String framework = jdbcTemplate.queryForObject(
                "SELECT v.clause_framework FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id "
                        + "WHERE t.template_no = ? AND v.version_no = 1", String.class, templateNo);
        return framework.split("\\{\"key\"").length - 1;
    }
}
