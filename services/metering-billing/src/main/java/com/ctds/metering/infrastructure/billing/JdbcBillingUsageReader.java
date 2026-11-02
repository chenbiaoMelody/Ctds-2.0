package com.ctds.metering.infrastructure.billing;

import com.ctds.metering.config.MeteringProperties;
import com.ctds.metering.domain.billing.BillingUsageMetrics;
import com.ctds.metering.domain.billing.BillingUsageReader;
import com.ctds.metering.domain.billing.MeteringEventRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 计量与计数只读实现（hifi §5-2）：本域 metering_flow + 跨 schema 只读
 * ctds_contract.contract_usage_counter / contract_usage_log；全部只读，零写上游。
 * "本期"按 occurred_at 落在自然月起止区间（与计量 R-M2 同口径）。
 */
@Repository
public class JdbcBillingUsageReader implements BillingUsageReader {

    private static final String COUNTER_SQL =
            "SELECT used_count FROM %s.contract_usage_counter WHERE contract_no = ?";
    private static final String USAGE_LOG_PERIOD_SQL =
            "SELECT COUNT(*) FROM %s.contract_usage_log WHERE contract_no = ? AND outcome = 'ALLOWED' "
                    + "AND occurred_at >= ? AND occurred_at <= ?";
    private static final String USAGE_LOG_ALL_SQL =
            "SELECT COUNT(*) FROM %s.contract_usage_log WHERE contract_no = ? AND outcome = 'ALLOWED'";
    private static final String FLOW_PERIOD_SQL =
            "SELECT COUNT(*) FROM metering_flow WHERE contract_no = ? "
                    + "AND occurred_at >= ? AND occurred_at <= ?";
    private static final String FLOW_ALL_SQL =
            "SELECT COUNT(*) FROM metering_flow WHERE contract_no = ?";
    private static final String EVENTS_SQL =
            "SELECT id, source_type, source_no, occurred_at FROM metering_flow WHERE contract_no = ? "
                    + "AND occurred_at >= ? AND occurred_at <= ? ORDER BY occurred_at, id";

    private final JdbcTemplate jdbcTemplate;
    private final String contractSchema;

    public JdbcBillingUsageReader(final JdbcTemplate jdbcTemplate,
            final MeteringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.contractSchema = properties.getSourceSchema().getContract();
    }

    @Override
    public BillingUsageMetrics metrics(final String contractNo, final LocalDateTime periodStart,
            final LocalDateTime periodEnd) {
        final List<Integer> counters = jdbcTemplate.query(
                String.format(COUNTER_SQL, contractSchema),
                (rs, rowNum) -> rs.getInt("used_count"), contractNo);
        final Integer counterUsedCount = counters.isEmpty() ? null : counters.get(0);
        final int allowedPeriod = count(String.format(USAGE_LOG_PERIOD_SQL, contractSchema),
                contractNo, periodStart, periodEnd);
        final int allowedAll = count(String.format(USAGE_LOG_ALL_SQL, contractSchema), contractNo);
        final int flowPeriod = count(FLOW_PERIOD_SQL, contractNo, periodStart, periodEnd);
        final int flowAll = count(FLOW_ALL_SQL, contractNo);
        return new BillingUsageMetrics(counterUsedCount, allowedPeriod, allowedAll, flowPeriod, flowAll);
    }

    @Override
    public List<MeteringEventRow> events(final String contractNo, final LocalDateTime periodStart,
            final LocalDateTime periodEnd) {
        return jdbcTemplate.query(EVENTS_SQL, this::mapEvent, contractNo,
                Timestamp.valueOf(periodStart), Timestamp.valueOf(periodEnd));
    }

    private int count(final String sql, final Object... args) {
        final Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value.intValue();
    }

    private MeteringEventRow mapEvent(final ResultSet rs, final int rowNum) throws SQLException {
        final Timestamp occurredAt = rs.getTimestamp("occurred_at");
        return new MeteringEventRow(rs.getLong("id"), rs.getString("source_type"),
                rs.getString("source_no"),
                occurredAt == null ? null : occurredAt.toLocalDateTime());
    }
}
