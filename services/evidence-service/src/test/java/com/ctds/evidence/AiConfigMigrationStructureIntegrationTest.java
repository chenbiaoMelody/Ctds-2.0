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
 * WBS-3.10.4 T7（hifi §2.2 / §7）：V6 迁移结构锚——真实 MySQL 8 容器实跑 Flyway V1~V6，
 * 断言三表落库、列面逐字对齐（无 prompt/内容列；api_key_ref 为唯一密钥相关列且为引用占位）、
 * seed 总控单例行 + 封闭清单四行（未配置态）+ 权限两点增量（沿 V5 先例同事务留痕）。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AiConfigMigrationStructureIntegrationTest {

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_aiconfig_mig_it");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void threeTablesExist() {
        final List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name LIKE 'ai_gateway%' ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("ai_gateway_config", "ai_gateway_config_change_log",
                "ai_gateway_provider_config");
    }

    @Test
    void gatewaySeedRowMatchesC111Defaults() {
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT enabled, default_provider, token_budget FROM ai_gateway_config WHERE id = 1");
        assertThat(row.get("enabled")).as("缺省关闭 = V1.0 零消耗安全默认（C-11.1 B3）").isEqualTo(0);
        assertThat(row.get("default_provider")).isEqualTo("glm");
        assertThat(row.get("token_budget")).as("0 = 未设限（预留位）").isEqualTo(0L);
    }

    @Test
    void providerSeedRowsAreClosedListUnconfigured() {
        final List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT provider_id, base_url, api_key_ref, model FROM ai_gateway_provider_config "
                        + "ORDER BY provider_id");
        assertThat(rows).extracting(r -> r.get("provider_id"))
                .containsExactly("deepseek", "glm", "kimi", "qwen");
        for (Map<String, Object> row : rows) {
            assertThat(row.get("base_url")).as("seed 未配置态：地址空").isNull();
            assertThat(row.get("api_key_ref")).as("seed 引用占位为空；真实密钥永不入库（红线）").isNull();
            assertThat(row.get("model")).as("seed 未配置态：模型名空").isNull();
        }
    }

    @Test
    void columnsHaveNoContentOrSecretMaterialColumns() {
        final List<String> configCols = columnsOf("ai_gateway_config");
        assertThat(configCols).containsExactly("id", "enabled", "default_provider", "token_budget",
                "updated_by", "updated_at");
        final List<String> providerCols = columnsOf("ai_gateway_provider_config");
        assertThat(providerCols).containsExactly("provider_id", "base_url", "api_key_ref", "model",
                "updated_at");
        final List<String> logCols = columnsOf("ai_gateway_config_change_log");
        assertThat(logCols).containsExactly("id", "changed_at", "changed_by", "field_path",
                "before_value", "after_value");
        final String all = String.join(",", providerCols) + "," + String.join(",", logCols);
        assertThat(all).as("零 prompt/响应原文列（V1.0 预留边界）").doesNotContain("PROMPT");
        assertThat(all).as("密钥相关列仅 api_key_ref 引用占位（无密钥值列）").doesNotContain("API_KEY_VALUE");
    }

    @Test
    void permissionSeedGrantsTwoPointsToSysadminWithChangeLog() {
        final Integer grants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_code = 'platform.sysadmin' "
                        + "AND permission_point IN ('platform.ai.config.read','platform.ai.config.write') "
                        + "AND enabled = 1", Integer.class);
        assertThat(grants).as("两新点仅授 platform.sysadmin（沿 V5 三员分立先例）").isEqualTo(2);
        final List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT change_no, change_order_no, changed_by, role_code, permission_point, "
                        + "before_value, after_value FROM role_permission_change_log "
                        + "WHERE permission_point LIKE 'platform.ai.config.%' ORDER BY permission_point");
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).get("change_no")).asString().startsWith("RCW3104");
        assertThat(logs.get(0).get("before_value")).isEqualTo("0");
        assertThat(logs.get(0).get("after_value")).isEqualTo("1");
    }

    private List<String> columnsOf(final String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }
}
