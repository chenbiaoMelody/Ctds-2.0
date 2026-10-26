package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 注册申请 / 档案 JdbcClient 仓储（hifi §2；生成列 active_name_norm 只读不写）。 */
@Repository
public class JdbcConnectorRepository implements ConnectorRepository {

    private static final String COLUMNS = "id, application_no, connector_no, subject_no, connector_name, "
            + "connector_type, connector_version, status, last_rejection_code, last_rejection_reason, "
            + "last_heartbeat_at, created_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcConnectorRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String nextApplicationNo() {
        jdbc.sql("UPDATE connector_application_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("CA%06d", next);
    }

    @Override
    @Transactional
    public String nextConnectorNo() {
        jdbc.sql("UPDATE connector_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1").update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("CN%06d", next);
    }

    @Override
    public void insert(final ConnectorRegistration registration) {
        jdbc.sql("INSERT INTO connector_registration (application_no, connector_no, subject_no, "
                        + "connector_name, connector_type, connector_version, status) "
                        + "VALUES (:applicationNo, NULL, :subjectNo, :name, :type, :version, :status)")
                .param("applicationNo", registration.applicationNo())
                .param("subjectNo", registration.subjectNo())
                .param("name", registration.connectorName())
                .param("type", registration.connectorType())
                .param("version", registration.connectorVersion())
                .param("status", registration.status().name())
                .update();
    }

    @Override
    public Optional<ConnectorRegistration> findByApplicationNo(final String applicationNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_registration WHERE application_no = :no")
                .param("no", applicationNo).query(JdbcConnectorRepository::map).optional();
    }

    @Override
    public Optional<ConnectorRegistration> findByConnectorNo(final String connectorNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_registration WHERE connector_no = :no")
                .param("no", connectorNo).query(JdbcConnectorRepository::map).optional();
    }

    @Override
    public boolean existsActiveName(final String subjectNo, final String connectorName,
            final String excludeApplicationNo) {
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM connector_registration "
                + "WHERE subject_no = :subject AND active_name_norm = LOWER(TRIM(:name)) "
                + "AND status <> 'CANCELLED'");
        if (excludeApplicationNo != null) {
            sql.append(" AND application_no <> :exclude");
        }
        final var spec = jdbc.sql(sql.toString()).param("subject", subjectNo).param("name", connectorName);
        if (excludeApplicationNo != null) {
            spec.param("exclude", excludeApplicationNo);
        }
        final Long count = spec.query(Long.class).single();
        return count != null && count > 0;
    }

    @Override
    public int amend(final String applicationNo, final String connectorName, final String connectorType,
            final String connectorVersion) {
        return jdbc.sql("UPDATE connector_registration SET connector_name = :name, connector_type = :type, "
                        + "connector_version = :version WHERE application_no = :no "
                        + "AND status = 'PENDING_REVIEW'")
                .param("name", connectorName).param("type", connectorType)
                .param("version", connectorVersion).param("no", applicationNo).update();
    }

    @Override
    public int markRegistered(final String applicationNo, final String connectorNo) {
        return jdbc.sql("UPDATE connector_registration SET connector_no = :connectorNo, "
                        + "status = 'REGISTERED' WHERE application_no = :no AND status = 'PENDING_REVIEW'")
                .param("connectorNo", connectorNo).param("no", applicationNo).update();
    }

    @Override
    public void markVerificationRejected(final String applicationNo, final String reasonCode,
            final String reason) {
        jdbc.sql("UPDATE connector_registration SET last_rejection_code = :code, "
                        + "last_rejection_reason = :reason WHERE application_no = :no "
                        + "AND status = 'PENDING_REVIEW'")
                .param("code", reasonCode).param("reason", reason).param("no", applicationNo).update();
    }

    @Override
    public int touchHeartbeat(final String connectorNo, final LocalDateTime now) {
        return jdbc.sql("UPDATE connector_registration SET last_heartbeat_at = :now "
                        + "WHERE connector_no = :no AND status = 'REGISTERED'")
                .param("now", now).param("no", connectorNo).update();
    }

    @Override
    public List<ConnectorRegistration> findRegistrations(final String subjectNo,
            final ConnectorStatus status, final long offset, final int limit) {
        final Map<String, Object> params = new HashMap<>();
        final String where = where(subjectNo, status, params);
        params.put("limit", limit);
        params.put("offset", offset);
        final var spec = jdbc.sql("SELECT " + COLUMNS + " FROM connector_registration" + where
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.forEach(spec::param);
        return spec.query(JdbcConnectorRepository::map).list();
    }

    @Override
    public List<ConnectorRegistration> findPendingQueue(final long offset, final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_registration "
                        + "WHERE status = 'PENDING_REVIEW' ORDER BY created_at ASC, id ASC "
                        + "LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset)
                .query(JdbcConnectorRepository::map).list();
    }

    @Override
    public long countRegistrations(final String subjectNo, final ConnectorStatus status) {
        final Map<String, Object> params = new HashMap<>();
        final String where = where(subjectNo, status, params);
        final var spec = jdbc.sql("SELECT COUNT(*) FROM connector_registration" + where);
        params.forEach(spec::param);
        final Long count = spec.query(Long.class).single();
        return count == null ? 0L : count;
    }

    private static String where(final String subjectNo, final ConnectorStatus status,
            final Map<String, Object> params) {
        final StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (subjectNo != null) {
            sql.append(" AND subject_no = :subject");
            params.put("subject", subjectNo);
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        return sql.toString();
    }

    private static ConnectorRegistration map(final ResultSet rs, final int rowNum) throws SQLException {
        return new ConnectorRegistration(
                rs.getLong("id"),
                rs.getString("application_no"),
                rs.getString("connector_no"),
                rs.getString("subject_no"),
                rs.getString("connector_name"),
                rs.getString("connector_type"),
                rs.getString("connector_version"),
                ConnectorStatus.of(rs.getString("status")),
                rs.getString("last_rejection_code"),
                rs.getString("last_rejection_reason"),
                rs.getObject("last_heartbeat_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
