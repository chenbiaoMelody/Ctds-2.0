package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.BindingStatus;
import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorBindingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 绑定 JdbcClient 仓储（hifi §2；不写 DID 状态快照）。 */
@Repository
public class JdbcConnectorBindingRepository implements ConnectorBindingRepository {

    private static final String COLUMNS = "id, connector_no, subject_no, did, status, bound_at, released_at";

    private final JdbcClient jdbc;

    public JdbcConnectorBindingRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final ConnectorBinding binding) {
        jdbc.sql("INSERT INTO connector_did_binding (connector_no, subject_no, did, status, bound_at) "
                        + "VALUES (:connectorNo, :subjectNo, :did, :status, :boundAt)")
                .param("connectorNo", binding.connectorNo())
                .param("subjectNo", binding.subjectNo())
                .param("did", binding.did())
                .param("status", binding.status().name())
                .param("boundAt", binding.boundAt())
                .update();
    }

    @Override
    public Optional<ConnectorBinding> findActiveByConnectorNo(final String connectorNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_did_binding "
                        + "WHERE connector_no = :no AND status = 'ACTIVE'")
                .param("no", connectorNo).query(JdbcConnectorBindingRepository::map).optional();
    }

    private static ConnectorBinding map(final ResultSet rs, final int rowNum) throws SQLException {
        return new ConnectorBinding(
                rs.getLong("id"),
                rs.getString("connector_no"),
                rs.getString("subject_no"),
                rs.getString("did"),
                BindingStatus.of(rs.getString("status")),
                rs.getObject("bound_at", LocalDateTime.class),
                rs.getObject("released_at", LocalDateTime.class));
    }
}
