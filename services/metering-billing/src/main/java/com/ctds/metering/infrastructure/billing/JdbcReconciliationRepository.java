package com.ctds.metering.infrastructure.billing;

import com.ctds.common.pagination.PageQuery;
import com.ctds.metering.domain.billing.Reconciliation;
import com.ctds.metering.domain.billing.ReconciliationConclusion;
import com.ctds.metering.domain.billing.ReconciliationRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 对账记录仓储 JDBC 实现（hifi §2）：仅插入与读取（对账记录不可改；不自动修数，B11-4）。
 */
@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final String COLUMNS = "id, contract_no, `period`, counter_used_count, "
            + "usage_log_allowed_count, metering_flow_count, usage_log_allowed_all_time, "
            + "metering_flow_all_time, bill_quantity, bill_total_amount, recomputed_amount, "
            + "conclusion, details_json, created_by, created_at";
    private static final String INSERT_SQL = "INSERT INTO billing_reconciliation (contract_no, "
            + "`period`, counter_used_count, usage_log_allowed_count, metering_flow_count, "
            + "usage_log_allowed_all_time, metering_flow_all_time, bill_quantity, "
            + "bill_total_amount, recomputed_amount, conclusion, details_json, created_by, "
            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconciliationRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(final Reconciliation reconciliation) {
        jdbcTemplate.update(INSERT_SQL, reconciliation.contractNo(), reconciliation.period(),
                reconciliation.counterUsedCount(), reconciliation.usageLogAllowedCount(),
                reconciliation.meteringFlowCount(), reconciliation.usageLogAllowedAllTime(),
                reconciliation.meteringFlowAllTime(), reconciliation.billQuantity(),
                reconciliation.billTotalAmount(), reconciliation.recomputedAmount(),
                reconciliation.conclusion().name(), reconciliation.detailsJson(),
                reconciliation.createdBy(), Timestamp.valueOf(reconciliation.createdAt()));
    }

    @Override
    public List<Reconciliation> find(final String contractNo, final String period,
            final PageQuery page) {
        final SqlArgs sqlArgs = new SqlArgs();
        sqlArgs.where.add("contract_no = ?");
        sqlArgs.args.add(contractNo);
        if (period != null) {
            sqlArgs.where.add("`period` = ?");
            sqlArgs.args.add(period);
        }
        sqlArgs.args.add(page.pageSize());
        sqlArgs.args.add(page.offset());
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM billing_reconciliation"
                + sqlArgs.whereSql() + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRow, sqlArgs.args.toArray());
    }

    @Override
    public long count(final String contractNo, final String period) {
        final StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM billing_reconciliation WHERE contract_no = ?");
        final List<Object> args = new ArrayList<>();
        args.add(contractNo);
        if (period != null) {
            sql.append(" AND `period` = ?");
            args.add(period);
        }
        final Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private Reconciliation mapRow(final ResultSet rs, final int rowNum) throws SQLException {
        final long id = rs.getLong("id");
        final String contractNo = rs.getString("contract_no");
        final String period = rs.getString("period");
        final int counterUsedCount = rs.getInt("counter_used_count");
        final int usageLogAllowedCount = rs.getInt("usage_log_allowed_count");
        final int meteringFlowCount = rs.getInt("metering_flow_count");
        final int usageLogAllowedAllTime = rs.getInt("usage_log_allowed_all_time");
        final int meteringFlowAllTime = rs.getInt("metering_flow_all_time");
        final int billQuantityRaw = rs.getInt("bill_quantity");
        final Integer billQuantity = rs.wasNull() ? null : billQuantityRaw;
        final BigDecimal billTotalAmount = rs.getBigDecimal("bill_total_amount");
        final BigDecimal recomputedAmount = rs.getBigDecimal("recomputed_amount");
        final ReconciliationConclusion conclusion =
                ReconciliationConclusion.valueOf(rs.getString("conclusion"));
        final String detailsJson = rs.getString("details_json");
        final String createdBy = rs.getString("created_by");
        final Timestamp createdAt = rs.getTimestamp("created_at");
        return new Reconciliation(id, contractNo, period, counterUsedCount, usageLogAllowedCount,
                meteringFlowCount, usageLogAllowedAllTime, meteringFlowAllTime, billQuantity,
                billTotalAmount, recomputedAmount, conclusion, detailsJson, createdBy,
                createdAt.toLocalDateTime());
    }

    /** WHERE 片段与参数累积器（片段为常量，值全部参数绑定）。 */
    private static final class SqlArgs {

        private final List<String> where = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        private String whereSql() {
            return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        }
    }
}
