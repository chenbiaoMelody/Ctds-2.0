package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorSource;
import com.ctds.connector.domain.ConnectorSourceCheck;
import com.ctds.connector.domain.ConnectorSourceRepository;
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

/**
 * 源登记 JdbcClient 仓储（hifi §2）：手写 SQL，沿 3.6.3 / 3.6.4 仓储形态。
 *
 * <p>自检结论不建表：{@link #findLastCheck} 从动作留痕取最近一条 {@code source.check}；
 * 留痕 detail 首段固定 {@code sourceNo=SRxxxxxx;}，保证按源可检索（同连接器多源互不串扰）。</p>
 */
@Repository
public class JdbcConnectorSourceRepository implements ConnectorSourceRepository {

    private static final String COLUMNS = "id, source_no, connector_no, subject_no, source_type, endpoint, "
            + "credential_ref, credential_ciphertext, credential_masked, created_at, updated_at";
    private static final String CHECK_ACTION = "source.check";
    private static final String SOURCE_NO_FIELD = "sourceNo";
    private static final String SOURCE_NO_PREFIX = SOURCE_NO_FIELD + "=";
    private static final String NULL_TEXT = "null";

    private final JdbcClient jdbc;

    public JdbcConnectorSourceRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String nextSourceNo() {
        jdbc.sql("UPDATE connector_source_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("SR%06d", next);
    }

    @Override
    public void insert(final ConnectorSource source) {
        jdbc.sql("INSERT INTO connector_source (source_no, connector_no, subject_no, source_type, endpoint, "
                        + "credential_ref, credential_ciphertext, credential_masked) "
                        + "VALUES (:sourceNo, :connectorNo, :subjectNo, :sourceType, :endpoint, "
                        + ":credentialRef, :credentialCiphertext, :credentialMasked)")
                .param("sourceNo", source.sourceNo())
                .param("connectorNo", source.connectorNo())
                .param("subjectNo", source.subjectNo())
                .param("sourceType", source.sourceType())
                .param("endpoint", source.endpoint())
                .param("credentialRef", source.credentialRef())
                .param("credentialCiphertext", source.credentialCiphertext())
                .param("credentialMasked", source.credentialMasked())
                .update();
    }

    @Override
    public int update(final String sourceNo, final String endpoint, final String credentialRef,
            final String credentialCiphertext, final String credentialMasked) {
        return jdbc.sql("UPDATE connector_source SET endpoint = :endpoint, credential_ref = :credentialRef, "
                        + "credential_ciphertext = :credentialCiphertext, "
                        + "credential_masked = :credentialMasked WHERE source_no = :sourceNo")
                .param("endpoint", endpoint)
                .param("credentialRef", credentialRef)
                .param("credentialCiphertext", credentialCiphertext)
                .param("credentialMasked", credentialMasked)
                .param("sourceNo", sourceNo)
                .update();
    }

    @Override
    public Optional<ConnectorSource> findBySourceNo(final String sourceNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_source WHERE source_no = :sourceNo")
                .param("sourceNo", sourceNo).query(JdbcConnectorSourceRepository::map).optional();
    }

    @Override
    public Optional<ConnectorSource> findByCredentialRef(final String credentialRef) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_source WHERE credential_ref = :ref")
                .param("ref", credentialRef).query(JdbcConnectorSourceRepository::map).optional();
    }

    @Override
    public List<ConnectorSource> listByConnector(final String connectorNo, final long offset,
            final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM connector_source WHERE connector_no = :no "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("no", connectorNo).param("limit", limit).param("offset", offset)
                .query(JdbcConnectorSourceRepository::map).list();
    }

    @Override
    public long countByConnector(final String connectorNo) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_source WHERE connector_no = :no")
                .param("no", connectorNo).query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public boolean existsDuplicate(final String connectorNo, final String sourceType,
            final String endpoint) {
        final Long count = jdbc.sql("SELECT COUNT(*) FROM connector_source WHERE connector_no = :no "
                        + "AND source_type = :type AND endpoint = :endpoint")
                .param("no", connectorNo).param("type", sourceType).param("endpoint", endpoint)
                .query(Long.class).single();
        return count != null && count > 0;
    }

    @Override
    public Optional<ConnectorSourceCheck> findLastCheck(final String connectorNo, final String sourceNo) {
        return jdbc.sql("SELECT detail, created_at FROM connector_action_log WHERE connector_no = :no "
                        + "AND action = :action AND result IN ('SUCCESS', 'FAILURE') AND detail LIKE :prefix "
                        + "ORDER BY id DESC LIMIT 1")
                .param("no", connectorNo).param("action", CHECK_ACTION)
                .param("prefix", SOURCE_NO_PREFIX + sourceNo + ";%")
                .query(JdbcConnectorSourceRepository::mapCheck).optional();
    }

    private static ConnectorSource map(final ResultSet rs, final int rowNum) throws SQLException {
        return new ConnectorSource(
                rs.getLong("id"),
                rs.getString("source_no"),
                rs.getString("connector_no"),
                rs.getString("subject_no"),
                rs.getString("source_type"),
                rs.getString("endpoint"),
                rs.getString("credential_ref"),
                rs.getString("credential_ciphertext"),
                rs.getString("credential_masked"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static ConnectorSourceCheck mapCheck(final ResultSet rs, final int rowNum) throws SQLException {
        final String detail = rs.getString("detail");
        final Map<String, String> fields = new HashMap<>();
        for (final String part : detail.split(";")) {
            final int separator = part.indexOf('=');
            if (separator > 0) {
                fields.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        final String failureType = fields.get("failureType");
        return new ConnectorSourceCheck(
                fields.get(SOURCE_NO_FIELD),
                "true".equals(fields.get("passed")),
                failureType == null || NULL_TEXT.equals(failureType) ? null : failureType,
                fields.get("message"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
