package com.ctds.metering.infrastructure.billing;

import com.ctds.metering.domain.billing.BillDetailLine;
import com.ctds.metering.domain.billing.BillDetailRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账单明细仓储 JDBC 实现（hifi §2）：只插入与读取，无更新或删除路径（账单不可变，B10-3）。
 */
@Repository
public class JdbcBillDetailRepository implements BillDetailRepository {

    private static final String INSERT_SQL = "INSERT INTO billing_bill_detail (bill_no, line_no, "
            + "line_type, description, metering_flow_id, source_type, source_no, occurred_at, "
            + "quantity, unit_price, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_SQL = "SELECT id, bill_no, line_no, line_type, description, "
            + "metering_flow_id, source_type, source_no, occurred_at, quantity, unit_price, amount "
            + "FROM billing_bill_detail WHERE bill_no = ? ORDER BY line_no";
    private static final String SELECT_FLOW_IDS_SQL = "SELECT metering_flow_id "
            + "FROM billing_bill_detail WHERE bill_no = ? AND metering_flow_id IS NOT NULL";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBillDetailRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertAll(final List<BillDetailLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, lines.stream().map(line -> new Object[] {
                line.billNo(), line.lineNo(), line.lineType(), line.description(),
                line.meteringFlowId(), line.sourceType(), line.sourceNo(),
                line.occurredAt() == null ? null : Timestamp.valueOf(line.occurredAt()),
                line.quantity(), line.unitPrice(), line.amount()}).toList());
    }

    @Override
    public List<BillDetailLine> findByBillNo(final String billNo) {
        return jdbcTemplate.query(SELECT_SQL, this::mapLine, billNo);
    }

    @Override
    public List<Long> findMeteringFlowIds(final String billNo) {
        return jdbcTemplate.query(SELECT_FLOW_IDS_SQL,
                (rs, rowNum) -> rs.getLong("metering_flow_id"), billNo);
    }

    private BillDetailLine mapLine(final ResultSet rs, final int rowNum) throws SQLException {
        final long id = rs.getLong("id");
        final String billNo = rs.getString("bill_no");
        final int lineNo = rs.getInt("line_no");
        final String lineType = rs.getString("line_type");
        final String description = rs.getString("description");
        final long flowId = rs.getLong("metering_flow_id");
        final Long meteringFlowId = rs.wasNull() ? null : flowId;
        final String sourceType = rs.getString("source_type");
        final String sourceNo = rs.getString("source_no");
        final Timestamp occurredAt = rs.getTimestamp("occurred_at");
        final int quantity = rs.getInt("quantity");
        final BigDecimal unitPrice = rs.getBigDecimal("unit_price");
        final BigDecimal amount = rs.getBigDecimal("amount");
        return new BillDetailLine(id, billNo, lineNo, lineType, description, meteringFlowId,
                sourceType, sourceNo, occurredAt == null ? null : occurredAt.toLocalDateTime(),
                quantity, unitPrice, amount);
    }
}
