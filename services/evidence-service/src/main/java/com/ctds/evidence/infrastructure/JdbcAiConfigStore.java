package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AiConfigChangeView;
import com.ctds.evidence.domain.AiConfigStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * WBS-3.10.4 Q2-A：AI 网关配置面三表本地读写（JDBC 实现沿 {@link JdbcRbacStore} 先例）。
 * 红线：api_key_ref 引用占位列不出现在任何 SELECT 列清单（出库即不触达）；
 * 保存与留痕由调用方（AiConfigService）以同一事务包住。
 */
@Repository
public class JdbcAiConfigStore implements AiConfigStore {

    private final JdbcTemplate jdbc;

    public JdbcAiConfigStore(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GatewayState loadGatewayState() {
        return jdbc.queryForObject(
                "SELECT enabled, default_provider, token_budget FROM ai_gateway_config WHERE id = 1",
                (rs, rowNum) -> new GatewayState(rs.getBoolean(1), rs.getString(2), rs.getLong(3)));
    }

    @Override
    public List<ProviderState> loadProviderStates() {
        return jdbc.query(
                "SELECT provider_id, base_url, model FROM ai_gateway_provider_config ORDER BY provider_id",
                (rs, rowNum) -> new ProviderState(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    @Override
    public void saveGatewayState(final boolean enabled, final String defaultProvider,
            final long tokenBudget, final String updatedBy) {
        jdbc.update("UPDATE ai_gateway_config SET enabled = ?, default_provider = ?, "
                        + "token_budget = ?, updated_by = ? WHERE id = 1",
                enabled ? 1 : 0, defaultProvider, tokenBudget, updatedBy);
    }

    @Override
    public void saveProviderState(final String providerId, final String baseUrl, final String model) {
        jdbc.update("UPDATE ai_gateway_provider_config SET base_url = ?, model = ? WHERE provider_id = ?",
                baseUrl, model, providerId);
    }

    @Override
    public void appendChanges(final List<ChangeEntry> entries) {
        for (ChangeEntry entry : entries) {
            jdbc.update("INSERT INTO ai_gateway_config_change_log "
                            + "(changed_by, field_path, before_value, after_value) VALUES (?, ?, ?, ?)",
                    entry.changedBy(), entry.fieldPath(), entry.beforeValue(), entry.afterValue());
        }
    }

    @Override
    public List<AiConfigChangeView> changes(final long offset, final int limit) {
        return jdbc.query("SELECT CAST(changed_at AS CHAR), changed_by, field_path, "
                        + "before_value, after_value FROM ai_gateway_config_change_log "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AiConfigChangeView(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)),
                limit, offset);
    }

    @Override
    public long countChanges() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_gateway_config_change_log", Long.class);
        return total == null ? 0L : total;
    }
}
