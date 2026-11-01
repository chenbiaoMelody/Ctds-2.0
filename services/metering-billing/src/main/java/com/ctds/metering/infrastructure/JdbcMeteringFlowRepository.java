package com.ctds.metering.infrastructure;

import com.ctds.metering.domain.MeteringFlow;
import com.ctds.metering.domain.MeteringFlowRepository;
import com.ctds.metering.domain.MeteringSourceType;
import com.ctds.metering.domain.PeriodFlowSummary;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
