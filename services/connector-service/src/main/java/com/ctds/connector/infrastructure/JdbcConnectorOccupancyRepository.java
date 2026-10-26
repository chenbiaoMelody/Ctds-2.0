package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorJobOccupancy;
import com.ctds.connector.domain.ConnectorOccupancyRepository;
import com.ctds.connector.domain.OccupancyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 在途作业占用 JdbcClient 仓储（hifi §2：同 (connector_no, job_ref) 幂等 upsert）。 */
@Repository
public class JdbcConnectorOccupancyRepository implements ConnectorOccupancyRepository {

    private static final String COLUMNS = "id, connector_no, job_ref, status, occupied_at, released_at";

    private final JdbcClient jdbc;

    public JdbcConnectorOccupancyRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void occupy(final String connectorNo, final String jobRef, final LocalDateTime now) {
        jdbc.sql("INSERT INTO connector_job_occupancy (connector_no, job_ref, status, occupied_at) "
                        + "VALUES (:no, :ref, 'OCCUPY', :now) "
                        + "ON DUPLICATE KEY UPDATE status = 'OCCUPY', occupied_at = :now, released_at = NULL")
                .param("no", connectorNo).param("ref", jobRef).param("now", now).update();
    }

    @Override
    public int release(final String connectorNo, final String jobRef, final LocalDateTime now) {
        return jdbc.sql("UPDATE connector_job_occupancy SET status = 'RELEASED', released_at = :now "
                        + "WHERE connector_no = :no AND job_ref = :ref AND status = 'OCCUPY'")
                .param("now", now).param("no", connectorNo).param("ref", jobRef).update();
    }

    @Override
    public Optional<ConnectorJobOccupancy> find(final String connectorNo, final String jobRef) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_job_occupancy "
                        + "WHERE connector_no = :no AND job_ref = :ref")
                .param("no", connectorNo).param("ref", jobRef)
                .query(JdbcConnectorOccupancyRepository::map).optional();
    }

    @Override
    public long countInProgress(final String connectorNo) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_job_occupancy "
                + "WHERE connector_no = :no AND status = 'OCCUPY'")
                .param("no", connectorNo).query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public List<ConnectorJobOccupancy> list(final String connectorNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_job_occupancy WHERE connector_no = :no "
                        + "ORDER BY occupied_at DESC, id DESC")
                .param("no", connectorNo).query(JdbcConnectorOccupancyRepository::map).list();
    }

    private static ConnectorJobOccupancy map(final ResultSet rs, final int rowNum) throws SQLException {
        return new ConnectorJobOccupancy(
                rs.getLong("id"),
                rs.getString("connector_no"),
                rs.getString("job_ref"),
                OccupancyStatus.of(rs.getString("status")),
                rs.getObject("occupied_at", LocalDateTime.class),
                rs.getObject("released_at", LocalDateTime.class));
    }
}
