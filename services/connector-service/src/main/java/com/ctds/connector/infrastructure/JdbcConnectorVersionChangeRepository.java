package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.Compatibility;
import com.ctds.connector.domain.ConnectorVersionChange;
import com.ctds.connector.domain.ConnectorVersionChangeRepository;
import com.ctds.connector.domain.VersionChangeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 版本变更 JdbcClient 仓储（hifi §2；待确认唯一由生成列 + 唯一索引兜底）。 */
@Repository
public class JdbcConnectorVersionChangeRepository implements ConnectorVersionChangeRepository {

    private static final String COLUMNS = "id, upgrade_no, connector_no, subject_no, from_version, "
            + "to_version, compatibility, status, requested_by, confirmed_by, applied_at, created_at";

    private final JdbcClient jdbc;

    public JdbcConnectorVersionChangeRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String nextUpgradeNo() {
        jdbc.sql("UPDATE connector_upgrade_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("UC%06d", next);
    }

    @Override
    public void insert(final ConnectorVersionChange change) {
        jdbc.sql("INSERT INTO connector_version_change (upgrade_no, connector_no, subject_no, from_version, "
                        + "to_version, compatibility, status, requested_by, confirmed_by, applied_at) "
                        + "VALUES (:upgradeNo, :connectorNo, :subjectNo, :fromVersion, :toVersion, "
                        + ":compatibility, :status, :requestedBy, :confirmedBy, :appliedAt)")
                .param("upgradeNo", change.upgradeNo())
                .param("connectorNo", change.connectorNo())
                .param("subjectNo", change.subjectNo())
                .param("fromVersion", change.fromVersion())
                .param("toVersion", change.toVersion())
                .param("compatibility", change.compatibility().name())
                .param("status", change.status().name())
                .param("requestedBy", change.requestedBy())
                .param("confirmedBy", change.confirmedBy())
                .param("appliedAt", change.appliedAt())
                .update();
    }

    @Override
    public Optional<ConnectorVersionChange> findByUpgradeNo(final String upgradeNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_version_change WHERE upgrade_no = :no")
                .param("no", upgradeNo).query(JdbcConnectorVersionChangeRepository::map).optional();
    }

    @Override
    public Optional<ConnectorVersionChange> findPendingByConnectorNo(final String connectorNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_version_change "
                        + "WHERE connector_no = :no AND status = 'PENDING_CONFIRM'")
                .param("no", connectorNo).query(JdbcConnectorVersionChangeRepository::map).optional();
    }

    @Override
    public List<ConnectorVersionChange> findByConnectorNo(final String connectorNo, final long offset,
            final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_version_change WHERE connector_no = :no "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("no", connectorNo).param("limit", limit).param("offset", offset)
                .query(JdbcConnectorVersionChangeRepository::map).list();
    }

    @Override
    public long countByConnectorNo(final String connectorNo) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_version_change WHERE connector_no = :no")
                .param("no", connectorNo).query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public List<ConnectorVersionChange> findPendingQueue(final long offset, final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_version_change "
                        + "WHERE status = 'PENDING_CONFIRM' ORDER BY created_at ASC, id ASC "
                        + "LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset)
                .query(JdbcConnectorVersionChangeRepository::map).list();
    }

    @Override
    public long countPending() {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_version_change "
                + "WHERE status = 'PENDING_CONFIRM'").query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public int apply(final String upgradeNo, final String confirmedBy, final LocalDateTime appliedAt) {
        return jdbc.sql("UPDATE connector_version_change SET status = 'APPLIED', confirmed_by = :by, "
                        + "applied_at = :at WHERE upgrade_no = :no AND status = 'PENDING_CONFIRM'")
                .param("by", confirmedBy).param("at", appliedAt).param("no", upgradeNo).update();
    }

    @Override
    public int voidPending(final String connectorNo) {
        return jdbc.sql("UPDATE connector_version_change SET status = 'VOIDED' "
                        + "WHERE connector_no = :no AND status = 'PENDING_CONFIRM'")
                .param("no", connectorNo).update();
    }

    private static ConnectorVersionChange map(final ResultSet rs, final int rowNum) throws SQLException {
        return new ConnectorVersionChange(
                rs.getLong("id"),
                rs.getString("upgrade_no"),
                rs.getString("connector_no"),
                rs.getString("subject_no"),
                rs.getString("from_version"),
                rs.getString("to_version"),
                Compatibility.parse(rs.getString("compatibility")),
                VersionChangeStatus.of(rs.getString("status")),
                rs.getString("requested_by"),
                rs.getString("confirmed_by"),
                rs.getObject("applied_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
