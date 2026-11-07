package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AuditQueryCriteria;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceStageType;
import com.ctds.evidence.domain.EvidenceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 监管审计读面查询仓储（WBS-3.8.4 hifi §2；只读——无任何写入方法，T21 源码断言机器可证）：
 * SQL 全部固定列名常量 + 占位符参数（沿 JdbcEvidenceRepository whereClause 先例）；
 * MISMATCH 判定 = 每条存证最新一条 VERIFIED 留痕 detail='MISMATCH'（MAX(id) 子查询锚定）。
 */
@Repository
public class JdbcEvidenceAuditQueryRepository implements EvidenceAuditQueryRepository {

    private static final String RECORD_COLUMNS =
            "id, evidence_no, stage_type, business_no, operation_type, subject_did_digest, "
                    + "occurred_at, content_digest, status, chain_tx_id, chain_block_no, "
                    + "retry_count, last_error_summary, created_at, updated_at";

    /** 异常项并集各分支的记录列（r. 前缀对齐）。 */
    private static final String UNION_RECORD_COLUMNS =
            "r.id, r.evidence_no, r.stage_type, r.business_no, r.operation_type, "
                    + "r.subject_did_digest, r.occurred_at, r.content_digest, r.status, "
                    + "r.chain_tx_id, r.chain_block_no, r.retry_count, r.last_error_summary, "
                    + "r.created_at, r.updated_at";

    /** 最新一条 VERIFIED 留痕子查询（MISMATCH 判定与核验结论共用）。 */
    private static final String LATEST_VERIFIED_SUBQUERY =
            "SELECT a.evidence_no, a.detail, a.actor_subject_no, a.created_at "
                    + "FROM evidence_record_action_log a JOIN (SELECT evidence_no, MAX(id) AS max_id "
                    + "FROM evidence_record_action_log WHERE action = 'VERIFIED' "
                    + "GROUP BY evidence_no) m ON a.id = m.max_id AND a.evidence_no = m.evidence_no";

    private static final String PENDING_BRANCH =
            "SELECT " + UNION_RECORD_COLUMNS + ", 'PENDING' AS anomaly_type, "
                    + "NULL AS verify_conclusion, NULL AS verified_by, NULL AS verified_at "
                    + "FROM evidence_record r WHERE r.status = 'PENDING'";

    private static final String FAILED_BRANCH =
            "SELECT " + UNION_RECORD_COLUMNS + ", 'FAILED' AS anomaly_type, "
                    + "NULL AS verify_conclusion, NULL AS verified_by, NULL AS verified_at "
                    + "FROM evidence_record r WHERE r.status = 'FAILED'";

    private static final String MISMATCH_BRANCH =
            "SELECT " + UNION_RECORD_COLUMNS + ", 'MISMATCH' AS anomaly_type, "
                    + "v.detail AS verify_conclusion, v.actor_subject_no AS verified_by, "
                    + "v.created_at AS verified_at "
                    + "FROM evidence_record r JOIN (" + LATEST_VERIFIED_SUBQUERY
                    + " WHERE a.detail = 'MISMATCH') v ON v.evidence_no = r.evidence_no";

    private static final RowMapper<EvidenceRecord> RECORD_MAPPER =
            (rs, rowNum) -> toRecord(rs);

    private final JdbcClient jdbc;

    public JdbcEvidenceAuditQueryRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StageStatusCount> countByStageAndStatus(final Instant from, final Instant to) {
        final Where where = windowWhere(from, to);
        return jdbc.sql("SELECT r.stage_type, r.status, COUNT(1) AS cnt FROM evidence_record r"
                        + where.sql() + " GROUP BY r.stage_type, r.status")
                .params(where.params())
                .query((rs, rowNum) -> new StageStatusCount(rs.getString("stage_type"),
                        rs.getString("status"), rs.getLong("cnt")))
                .list();
    }

    @Override
    public long countMismatch(final Instant from, final Instant to) {
        final Where where = windowWhere(from, to);
        final List<Long> values = jdbc.sql("SELECT COUNT(1) FROM evidence_record r JOIN ("
                        + LATEST_VERIFIED_SUBQUERY + " WHERE a.detail = 'MISMATCH') v "
                        + "ON v.evidence_no = r.evidence_no" + where.sql())
                .params(where.params())
                .query(Long.class)
                .list();
        return values.isEmpty() || values.get(0) == null ? 0L : values.get(0);
    }

    @Override
    public AnomalyPage pageAnomalies(final AuditQueryCriteria criteria, final int offset,
            final int limit) {
        // 三支并集共享同一组条件（SQL 片段与参数按分支顺序逐份展开）；核验不一致分支 join 最新 VERIFIED 留痕
        final StringBuilder unionSql = new StringBuilder(PENDING_BRANCH)
                .append(windowFragment(criteria));
        final List<Object> params = new ArrayList<>();
        appendWindow(criteria, params);
        unionSql.append(" UNION ALL ").append(FAILED_BRANCH).append(windowFragment(criteria));
        appendWindow(criteria, params);
        unionSql.append(" UNION ALL ").append(MISMATCH_BRANCH).append(windowFragment(criteria));
        appendWindow(criteria, params);
        final String sql = unionSql.toString();

        final List<Long> counts = jdbc.sql("SELECT COUNT(1) FROM (" + sql + ") t")
                .params(params)
                .query(Long.class)
                .list();
        final long total = counts.isEmpty() || counts.get(0) == null ? 0L : counts.get(0);

        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);
        final List<AnomalyRow> list = jdbc.sql("SELECT * FROM (" + sql + ") t "
                        + "ORDER BY t.occurred_at DESC, t.id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> new AnomalyRow(toRecord(rs), rs.getString("anomaly_type"),
                        rs.getString("verify_conclusion"), rs.getString("verified_by"),
                        toInstant(rs.getObject("verified_at", LocalDateTime.class))))
                .list();
        return new AnomalyPage(list, total);
    }

    @Override
    public EvidencePage pageByCriteria(final AuditQueryCriteria criteria, final int offset,
            final int limit) {
        final Where where = criteriaWhere(criteria);
        final List<Object> pageParams = new ArrayList<>(where.params());
        pageParams.add(limit);
        pageParams.add(offset);
        final List<EvidenceRecord> list = jdbc.sql("SELECT " + RECORD_COLUMNS
                        + " FROM evidence_record r" + where.sql()
                        + " ORDER BY r.occurred_at DESC, r.id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query(RECORD_MAPPER)
                .list();
        final List<Long> counts = jdbc.sql("SELECT COUNT(1) FROM evidence_record r" + where.sql())
                .params(where.params())
                .query(Long.class)
                .list();
        return new EvidencePage(list, counts.isEmpty() || counts.get(0) == null
                ? 0L : counts.get(0));
    }

    @Override
    public Optional<EvidenceRecord> findLatestByBusinessNo(final String businessNo) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM evidence_record "
                        + "WHERE business_no = ? ORDER BY occurred_at DESC, id DESC LIMIT 1")
                .param(businessNo)
                .query(RECORD_MAPPER)
                .optional();
    }

    @Override
    public List<VerdictRow> latestVerdicts(final AuditQueryCriteria criteria) {
        final Where where = criteriaWhere(criteria);
        return jdbc.sql("SELECT r.evidence_no, v.detail AS conclusion, "
                        + "v.actor_subject_no AS verified_by, v.created_at AS verified_at "
                        + "FROM evidence_record r LEFT JOIN (" + LATEST_VERIFIED_SUBQUERY
                        + ") v ON v.evidence_no = r.evidence_no" + where.sql()
                        + " ORDER BY r.occurred_at DESC, r.id DESC")
                .params(where.params())
                .query((rs, rowNum) -> rs.getString("conclusion") == null ? null
                        : new VerdictRow(rs.getString("evidence_no"), rs.getString("conclusion"),
                                rs.getString("verified_by"),
                                toInstant(rs.getObject("verified_at", LocalDateTime.class))))
                .list()
                .stream().filter(Objects::nonNull).toList();
    }

    @Override
    public Optional<VerdictRow> latestVerdict(final String evidenceNo) {
        return jdbc.sql("SELECT a.evidence_no, a.detail AS conclusion, "
                        + "a.actor_subject_no AS verified_by, a.created_at AS verified_at "
                        + "FROM evidence_record_action_log a WHERE a.action = 'VERIFIED' "
                        + "AND a.evidence_no = ? ORDER BY a.id DESC LIMIT 1")
                .param(evidenceNo)
                .query((rs, rowNum) -> new VerdictRow(rs.getString("evidence_no"),
                        rs.getString("conclusion"), rs.getString("verified_by"),
                        toInstant(rs.getObject("verified_at", LocalDateTime.class))))
                .optional();
    }

    // ==== 内部：条件片段与行映射 ====

    private static void appendWindow(final AuditQueryCriteria criteria,
            final List<Object> params) {
        if (criteria.stageType() != null) {
            params.add(criteria.stageType().name());
        }
        if (criteria.businessNo() != null) {
            params.add(criteria.businessNo());
        }
        if (criteria.subjectDigest() != null) {
            params.add(criteria.subjectDigest());
        }
        if (criteria.from() != null) {
            params.add(toLocal(criteria.from()));
        }
        if (criteria.to() != null) {
            params.add(toLocal(criteria.to()));
        }
    }

    /** 条件 WHERE 片段（列名固定常量，参数全部占位符；不追加参数——由调用方按序展开）。 */
    private static String windowFragment(final AuditQueryCriteria criteria) {
        final StringBuilder fragment = new StringBuilder();
        if (criteria.stageType() != null) {
            fragment.append(" AND r.stage_type = ?");
        }
        if (criteria.businessNo() != null) {
            fragment.append(" AND r.business_no = ?");
        }
        if (criteria.subjectDigest() != null) {
            fragment.append(" AND r.subject_did_digest = ?");
        }
        if (criteria.from() != null) {
            fragment.append(" AND r.occurred_at >= ?");
        }
        if (criteria.to() != null) {
            fragment.append(" AND r.occurred_at <= ?");
        }
        return fragment.toString();
    }

    private static Where windowWhere(final Instant from, final Instant to) {
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append(" WHERE r.occurred_at >= ?");
            params.add(toLocal(from));
        }
        if (to != null) {
            sql.append(sql.isEmpty() ? " WHERE" : " AND").append(" r.occurred_at <= ?");
            params.add(toLocal(to));
        }
        return new Where(sql.toString(), params);
    }

    private static Where criteriaWhere(final AuditQueryCriteria criteria) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (criteria.stageType() != null) {
            clauses.add("r.stage_type = ?");
            params.add(criteria.stageType().name());
        }
        if (criteria.businessNo() != null) {
            clauses.add("r.business_no = ?");
            params.add(criteria.businessNo());
        }
        if (criteria.subjectDigest() != null) {
            clauses.add("r.subject_did_digest = ?");
            params.add(criteria.subjectDigest());
        }
        if (criteria.from() != null) {
            clauses.add("r.occurred_at >= ?");
            params.add(toLocal(criteria.from()));
        }
        if (criteria.to() != null) {
            clauses.add("r.occurred_at <= ?");
            params.add(toLocal(criteria.to()));
        }
        final String sql = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Where(sql, params);
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

    private static Instant toInstant(final LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocal(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** WHERE 片段与参数（列名固定常量，参数全部占位符——沿 JdbcEvidenceRepository 先例）。 */
    private record Where(String sql, List<Object> params) {
    }
}
