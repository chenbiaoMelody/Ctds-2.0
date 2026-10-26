package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorActionLog;
import com.ctds.connector.domain.ConnectorLogRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 动作留痕 JdbcClient 仓储（只插不改；hifi §2）。 */
@Repository
public class JdbcConnectorLogRepository implements ConnectorLogRepository {

    private static final String COLUMNS = "application_no, connector_no, operator_no, action, result, "
            + "reason_code, detail, created_at";

    private final JdbcClient jdbc;

    public JdbcConnectorLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final ConnectorActionLog log) {
        jdbc.sql("INSERT INTO connector_action_log (application_no, connector_no, operator_no, action, "
                        + "result, reason_code, detail) VALUES (:applicationNo, :connectorNo, :operatorNo, "
                        + ":action, :result, :reasonCode, :detail)")
                .param("applicationNo", log.applicationNo())
                .param("connectorNo", log.connectorNo())
                .param("operatorNo", log.operatorNo())
                .param("action", log.action())
                .param("result", log.result())
                .param("reasonCode", log.reasonCode())
                .param("detail", log.detail())
                .update();
    }

    @Override
    public void attachConnectorNo(final String applicationNo, final String connectorNo) {
        jdbc.sql("UPDATE connector_action_log SET connector_no = :connectorNo "
                        + "WHERE application_no = :applicationNo AND connector_no IS NULL")
                .param("connectorNo", connectorNo).param("applicationNo", applicationNo).update();
    }

    @Override
    public List<ConnectorActionLog> findByConnectorNo(final String connectorNo, final long offset,
            final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_action_log WHERE connector_no = :no "
                        + "ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset")
                .param("no", connectorNo).param("limit", limit).param("offset", offset)
                .query((rs, rowNum) -> new ConnectorActionLog(
                        rs.getString("application_no"), rs.getString("connector_no"),
                        rs.getString("operator_no"), rs.getString("action"), rs.getString("result"),
                        rs.getString("reason_code"), rs.getString("detail"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)))
                .list();
    }

    @Override
    public long countByConnectorNo(final String connectorNo) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_action_log WHERE connector_no = :no")
                .param("no", connectorNo).query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public List<ConnectorActionLog> findByApplicationNo(final String applicationNo, final long offset,
            final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_action_log WHERE application_no = :no "
                        + "ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset")
                .param("no", applicationNo).param("limit", limit).param("offset", offset)
                .query((rs, rowNum) -> new ConnectorActionLog(
                        rs.getString("application_no"), rs.getString("connector_no"),
                        rs.getString("operator_no"), rs.getString("action"), rs.getString("result"),
                        rs.getString("reason_code"), rs.getString("detail"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)))
                .list();
    }

    @Override
    public long countByApplicationNo(final String applicationNo) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_action_log WHERE application_no = :no")
                .param("no", applicationNo).query(Long.class).single();
        return count == null ? 0L : count;
    }
}
