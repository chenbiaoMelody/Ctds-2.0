package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.EvidenceAction;
import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.ctds.evidence.domain.EvidenceSearchCriteria;
import com.ctds.evidence.domain.EvidenceStageType;
import com.ctds.evidence.domain.EvidenceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MySQL 存证仓储（JdbcClient，ADR-009 迁移建表）。
 *
 * <p>结构性约束（hifi §2）：只提供追加（insert / appendAction）与状态列推进（updateStatus）。
 * 内容列（编号 / 环节 / 单据号 / 操作类型 / DID 摘要 / 事件时点 / 内容摘要 / 创建时点）
 * 不出现在 UPDATE 子句（T9 源码断言机器可证）。时间统一以 UTC 墙钟存取，避免连接时区漂移。</p>
 */
@Repository
public class JdbcEvidenceRepository implements EvidenceRepository {

    private static final String SELECT_COLUMNS =
            "id, evidence_no, stage_type, business_no, operation_type, subject_did_digest, "
                    + "occurred_at, content_digest, status, chain_tx_id, chain_block_no, retry_count, "
                    + "last_error_summary, created_at, updated_at";
    private static final String UPDATE_STATUS_SQL =
            "UPDATE evidence_record SET status = ?, chain_tx_id = ?, chain_block_no = ?, "
                    + "retry_count = ?, last_error_summary = ?, updated_at = ? WHERE id = ?";

    private static final RowMapper<EvidenceRecord> RECORD_MAPPER =
            (rs, rowNum) -> toRecord(rs);
    private static final RowMapper<EvidenceActionLog> ACTION_MAPPER =
            (rs, rowNum) -> toActionLog(rs);

    private final JdbcClient jdbc;

    public JdbcEvidenceRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final EvidenceRecord record) {
        jdbc.sql("INSERT INTO evidence_record (evidence_no, stage_type, business_no, operation_type, "
                        + "subject_did_digest, occurred_at, content_digest, status, chain_tx_id, "
                        + "chain_block_no, retry_count, last_error_summary, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(record.evidenceNo(), record.stageType().name(), record.businessNo(),
                        record.operationType().name(), record.subjectDidDigest(),
                        toLocal(record.occurredAt()), record.contentDigest(), record.status().name(),
                        record.chainTxId(), record.chainBlockNo(), record.retryCount(),
                        record.lastErrorSummary(), toLocal(record.createdAt()),
                        toLocal(record.updatedAt()))
                .update();
    }

    @Override
    public void updateStatus(final EvidenceRecord record) {
        jdbc.sql(UPDATE_STATUS_SQL)
                .params(record.status().name(), record.chainTxId(), record.chainBlockNo(),
                        record.retryCount(), record.lastErrorSummary(),
                        toLocal(record.updatedAt()), record.id())
                .update();
    }

    @Override
    public void appendAction(final EvidenceActionLog action) {
        jdbc.sql("INSERT INTO evidence_record_action_log (evidence_no, action, from_status, "
                        + "to_status, actor_subject_no, reason_code, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(action.evidenceNo(), action.action().name(),
                        action.fromStatus() == null ? null : action.fromStatus().name(),
                        action.toStatus() == null ? null : action.toStatus().name(),
                        action.actorSubjectNo(), action.reasonCode(), action.detail(),
                        toLocal(action.createdAt()))
                .update();
    }

    @Override
    public Optional<EvidenceRecord> findByNo(final String evidenceNo) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM evidence_record WHERE evidence_no = ?")
                .param(evidenceNo)
                .query(RECORD_MAPPER)
                .optional();
    }

    @Override
    public Optional<EvidenceRecord> findByIdempotentKey(final EvidenceStageType stageType,
            final String businessNo, final EvidenceOperationType operationType) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM evidence_record "
                        + "WHERE stage_type = ? AND business_no = ? AND operation_type = ?")
                .params(stageType.name(), businessNo, operationType.name())
                .query(RECORD_MAPPER)
                .optional();
    }

    @Override
    public EvidencePage page(final EvidenceSearchCriteria criteria, final int offset, final int limit) {
        final QueryParts where = whereClause(criteria);
        final List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        final List<EvidenceRecord> list = jdbc.sql(
                        "SELECT " + SELECT_COLUMNS + " FROM evidence_record" + where.sql()
                                + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query(RECORD_MAPPER)
                .list();
        return new EvidencePage(list, count(criteria));
    }

    @Override
    public long countByStatus(final EvidenceStatus status) {
        final List<Long> values = jdbc.sql("SELECT COUNT(1) FROM evidence_record WHERE status = ?")
                .param(status.name())
                .query(Long.class)
                .list();
        return (values.isEmpty() || values.get(0) == null) ? 0L : values.get(0);
    }

    @Override
    public Optional<Instant> lastFailedAt() {
        final List<LocalDateTime> values = jdbc.sql(
                        "SELECT MAX(updated_at) FROM evidence_record WHERE status = ?")
                .param(EvidenceStatus.FAILED.name())
                .query(LocalDateTime.class)
                .list();
        if (values.isEmpty() || values.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(values.get(0).toInstant(ZoneOffset.UTC));
    }

    @Override
    public List<EvidenceActionLog> findActions(final String evidenceNo) {
        return jdbc.sql("SELECT id, evidence_no, action, from_status, to_status, actor_subject_no, "
                        + "reason_code, detail, created_at FROM evidence_record_action_log "
                        + "WHERE evidence_no = ? ORDER BY id ASC")
                .param(evidenceNo)
                .query(ACTION_MAPPER)
                .list();
    }

    private long count(final EvidenceSearchCriteria criteria) {
        final QueryParts where = whereClause(criteria);
        final List<Long> values = jdbc.sql("SELECT COUNT(1) FROM evidence_record" + where.sql())
                .params(where.params())
                .query(Long.class)
                .list();
        return (values.isEmpty() || values.get(0) == null) ? 0L : values.get(0);
    }

    private static QueryParts whereClause(final EvidenceSearchCriteria criteria) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (criteria.stageType() != null) {
            clauses.add("stage_type = ?");
            params.add(criteria.stageType().name());
        }
        if (criteria.businessNo() != null) {
            clauses.add("business_no = ?");
            params.add(criteria.businessNo());
        }
        if (criteria.status() != null) {
            clauses.add("status = ?");
            params.add(criteria.status().name());
        }
        if (criteria.from() != null) {
            clauses.add("occurred_at >= ?");
            params.add(toLocal(criteria.from()));
        }
        if (criteria.to() != null) {
            clauses.add("occurred_at <= ?");
            params.add(toLocal(criteria.to()));
        }
        final String sql = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new QueryParts(sql, params);
    }

    private static EvidenceRecord toRecord(final ResultSet rs) throws SQLException {
        return new EvidenceRecord(
                rs.getLong("id"),
                rs.getString("evidence_no"),
                EvidenceStageType.valueOf(rs.getString("stage_type")),
                rs.getString("business_no"),
                EvidenceOperationType.valueOf(rs.getString("operation_type")),
                rs.getString("subject_did_digest"),
                toInstant(rs.getObject("occurred_at", LocalDateTime.class)),
                rs.getString("content_digest"),
                EvidenceStatus.valueOf(rs.getString("status")),
                rs.getString("chain_tx_id"),
                rs.getObject("chain_block_no", Long.class),
                rs.getInt("retry_count"),
                rs.getString("last_error_summary"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    private static EvidenceActionLog toActionLog(final ResultSet rs) throws SQLException {
        return new EvidenceActionLog(
                rs.getLong("id"),
                rs.getString("evidence_no"),
                EvidenceAction.valueOf(rs.getString("action")),
                rs.getString("from_status") == null ? null
                        : EvidenceStatus.valueOf(rs.getString("from_status")),
                rs.getString("to_status") == null ? null
                        : EvidenceStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor_subject_no"),
                rs.getString("reason_code"),
                rs.getString("detail"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    private static LocalDateTime toLocal(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(final LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    /** WHERE 片段与参数（动态条件；列名固定常量，参数全部占位符）。 */
    private record QueryParts(String sql, List<Object> params) {
    }
}
