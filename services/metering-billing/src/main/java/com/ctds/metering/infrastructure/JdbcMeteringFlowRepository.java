package com.ctds.metering.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.metering.domain.FlowSearchCriteria;
import com.ctds.metering.domain.MeteringFlow;
import com.ctds.metering.domain.MeteringFlowRepository;
import com.ctds.metering.domain.MeteringFlowRow;
import com.ctds.metering.domain.MeteringSourceType;
import com.ctds.metering.domain.PeriodFlowSummary;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * metering_flow 仓储 JDBC 实现（hifi §2/§3）：幂等锚 uk(source_type, source_no)；
 * 聚合 = GROUP BY 合约 × 自然月查询承载（Q2-A 无物化表，idx_flow_contract 前缀覆盖）。
 * 落库 SQL 用 INSERT IGNORE（E4 勘误：hifi §3 原文 ON DUPLICATE KEY UPDATE id=id 在
 * Connector/J 默认 CLIENT_FOUND_ROWS 语义下空转 affected=1 与新增不可判别——集成实测证伪；
 * INSERT IGNORE 语义等价〔重复源单号零写入零计数〕且受影响行判别可靠：新增 = 1，重复 = 0）。
 */
@Repository
public class JdbcMeteringFlowRepository implements MeteringFlowRepository {

    private static final String UPSERT_SQL =
            "INSERT IGNORE INTO metering_flow (contract_no, requester_no, source_type, source_no, "
                    + "action, resource, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_CONTRACT_SQL =
            "SELECT contract_no, requester_no, source_type, source_no, action, resource, occurred_at "
                    + "FROM metering_flow WHERE contract_no = ? ORDER BY occurred_at DESC, id DESC";
    private static final String SUMMARIZE_SQL =
            "SELECT DATE_FORMAT(occurred_at, '%Y-%m') AS period, COUNT(*) AS flow_count, "
                    + "MAX(occurred_at) AS last_event_at FROM metering_flow WHERE contract_no = ? "
                    + "GROUP BY DATE_FORMAT(occurred_at, '%Y-%m') ORDER BY period DESC";
    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM metering_flow WHERE contract_no = ?";
    /** 治理读面列面（WBS-3.7.7 R-M4 hifi §1：九字段最小投影，零敏感列）。 */
    private static final String GOVERNANCE_COLUMNS =
            "id, contract_no, requester_no, source_type, source_no, action, resource, "
                    + "occurred_at, created_at";
    private static final String SELECT_GOVERNANCE_SQL =
            "SELECT " + GOVERNANCE_COLUMNS + " FROM metering_flow";

    private final JdbcTemplate jdbcTemplate;

    public JdbcMeteringFlowRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean upsert(final MeteringFlow flow) {
        final int affected = jdbcTemplate.update(UPSERT_SQL, flow.contractNo(), flow.requesterNo(),
                flow.sourceType().name(), flow.sourceNo(), flow.action(), flow.resource(),
                Timestamp.valueOf(flow.occurredAt()));
        // INSERT IGNORE + uk：新增 = 1，重复空转 = 0（不受 CLIENT_FOUND_ROWS 语义影响）
        return affected == 1;
    }

    @Override
    public List<MeteringFlow> findByContract(final String contractNo) {
        return jdbcTemplate.query(SELECT_BY_CONTRACT_SQL, (rs, rowNum) -> new MeteringFlow(
                rs.getString("contract_no"), rs.getString("requester_no"),
                MeteringSourceType.valueOf(rs.getString("source_type")), rs.getString("source_no"),
                rs.getString("action"), rs.getString("resource"),
                toLocalDateTime(rs.getTimestamp("occurred_at"))), contractNo);
    }

    @Override
    public List<PeriodFlowSummary> summarizeByPeriod(final String contractNo) {
        return jdbcTemplate.query(SUMMARIZE_SQL, (rs, rowNum) -> new PeriodFlowSummary(
                rs.getString("period"), rs.getLong("flow_count"),
                toLocalDateTime(rs.getTimestamp("last_event_at"))), contractNo);
    }

    @Override
    public long countByContract(final String contractNo) {
        final Long count = jdbcTemplate.queryForObject(COUNT_SQL, Long.class, contractNo);
        return count == null ? 0L : count;
    }

    @Override
    public List<MeteringFlowRow> findGovernance(final FlowSearchCriteria criteria,
            final PageQuery page) {
        final SqlArgs sqlArgs = new SqlArgs();
        applyCriteria(criteria, sqlArgs);
        sqlArgs.args.add(page.pageSize());
        sqlArgs.args.add(page.offset());
        return jdbcTemplate.query(SELECT_GOVERNANCE_SQL + sqlArgs.whereSql()
                        + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new MeteringFlowRow(
                        rs.getLong("id"), rs.getString("contract_no"),
                        rs.getString("requester_no"),
                        MeteringSourceType.valueOf(rs.getString("source_type")),
                        rs.getString("source_no"), rs.getString("action"), rs.getString("resource"),
                        toLocalDateTime(rs.getTimestamp("occurred_at")),
                        toLocalDateTime(rs.getTimestamp("created_at"))),
                sqlArgs.args.toArray());
    }

    @Override
    public long countGovernance(final FlowSearchCriteria criteria) {
        final SqlArgs sqlArgs = new SqlArgs();
        applyCriteria(criteria, sqlArgs);
        final Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow" + sqlArgs.whereSql(), Long.class,
                sqlArgs.args.toArray());
        return total == null ? 0L : total;
    }

    /** 五维过滤拼装（hifi §1 R-M4）：空值不参与过滤；时间区间按 occurred_at 闭区间。 */
    private static void applyCriteria(final FlowSearchCriteria criteria, final SqlArgs sqlArgs) {
        if (criteria.contractNo() != null) {
            sqlArgs.where.add("contract_no = ?");
            sqlArgs.args.add(criteria.contractNo());
        }
        if (criteria.requesterNo() != null) {
            sqlArgs.where.add("requester_no = ?");
            sqlArgs.args.add(criteria.requesterNo());
        }
        if (criteria.sourceType() != null) {
            sqlArgs.where.add("source_type = ?");
            sqlArgs.args.add(criteria.sourceType().name());
        }
        if (criteria.from() != null) {
            sqlArgs.where.add("occurred_at >= ?");
            sqlArgs.args.add(Timestamp.valueOf(criteria.from()));
        }
        if (criteria.to() != null) {
            sqlArgs.where.add("occurred_at <= ?");
            sqlArgs.args.add(Timestamp.valueOf(criteria.to()));
        }
    }

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /** WHERE 子句与参数累加器（与 JdbcBillRepository 同型；横切收敛见 DB-103 ③）。 */
    private static final class SqlArgs {
        private final List<String> where = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        private String whereSql() {
            return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        }
    }
}
