package com.ctds.metering.infrastructure.billing;

import com.ctds.metering.domain.billing.BillActionLog;
import com.ctds.metering.domain.billing.BillActionLogRepository;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillingAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账单动作留痕仓储 JDBC 实现（hifi §2）：仅插入与按账单读取（留痕不可改）。
 */
@Repository
public class JdbcBillActionLogRepository implements BillActionLogRepository {

    private static final String INSERT_SQL = "INSERT INTO billing_bill_action_log (bill_no, action, "
            + "from_status, to_status, reason, operator_subject_no, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_SQL = "SELECT bill_no, action, from_status, to_status, "
            + "reason, operator_subject_no, created_at FROM billing_bill_action_log "
            + "WHERE bill_no = ? ORDER BY created_at, id";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBillActionLogRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(final BillActionLog actionLog) {
        jdbcTemplate.update(INSERT_SQL, actionLog.billNo(), actionLog.action().name(),
                actionLog.fromStatus() == null ? null : actionLog.fromStatus().name(),
                actionLog.toStatus() == null ? null : actionLog.toStatus().name(),
                actionLog.reason(), actionLog.operatorSubjectNo(),
                Timestamp.valueOf(actionLog.createdAt()));
    }

    @Override
    public List<BillActionLog> findByBillNo(final String billNo) {
        return jdbcTemplate.query(SELECT_SQL, this::mapLog, billNo);
    }

    private BillActionLog mapLog(final ResultSet rs, final int rowNum) throws SQLException {
        final String fromStatus = rs.getString("from_status");
        final String toStatus = rs.getString("to_status");
        return new BillActionLog(rs.getString("bill_no"),
                BillingAction.valueOf(rs.getString("action")),
                fromStatus == null ? null : BillStatus.valueOf(fromStatus),
                toStatus == null ? null : BillStatus.valueOf(toStatus),
                rs.getString("reason"), rs.getString("operator_subject_no"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
