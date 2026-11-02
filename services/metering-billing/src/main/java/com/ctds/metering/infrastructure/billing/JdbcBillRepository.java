package com.ctds.metering.infrastructure.billing;

import com.ctds.common.pagination.PageQuery;
import com.ctds.metering.domain.billing.Bill;
import com.ctds.metering.domain.billing.BillRepository;
import com.ctds.metering.domain.billing.BillSearchCriteria;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillType;
import com.ctds.metering.domain.billing.BillingPricingModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账单仓储 JDBC 实现（hifi §2）：插入 + 读面检索 + 三种状态流转（确认 / 冻结 / 解冻）。
 * 无金额或明细更新 SQL（账单不可变，B10-3）；查询条件全部走参数绑定，排序固定。
 */
@Repository
public class JdbcBillRepository implements BillRepository {

    private static final String COLUMNS = "id, bill_no, bill_type, contract_no, requester_no, "
            + "provider_no, product_id, product_name, `period`, pricing_model, quantity, unit_price, "
            + "base_amount, share_rate, amount, status, status_before_freeze, correction_of_bill_no, "
            + "created_by, created_at";
    private static final String INSERT_SQL = "INSERT INTO billing_bill (bill_no, bill_type, "
            + "contract_no, requester_no, provider_no, product_id, product_name, `period`, "
            + "pricing_model, quantity, unit_price, base_amount, share_rate, amount, status, "
            + "status_before_freeze, correction_of_bill_no, created_by, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_NO_SQL =
            "SELECT " + COLUMNS + " FROM billing_bill WHERE bill_no = ?";
    private static final String SELECT_NORMAL_SQL = "SELECT " + COLUMNS + " FROM billing_bill "
            + "WHERE contract_no = ? AND `period` = ? AND bill_type = 'NORMAL'";
    private static final String SELECT_CORRECTIONS_SQL = "SELECT " + COLUMNS + " FROM billing_bill "
            + "WHERE correction_of_bill_no = ? ORDER BY created_at, id";
    private static final String SELECT_BY_CONTRACT_PERIOD_SQL = "SELECT " + COLUMNS
            + " FROM billing_bill WHERE contract_no = ? AND `period` = ? ORDER BY id FOR UPDATE";
    private static final String CONFIRM_SQL = "UPDATE billing_bill SET status = 'CONFIRMED' "
            + "WHERE bill_no = ? AND status = 'ISSUED'";
    /** 冻结前状态取行内真值（顺序不可颠倒：先存原状态，再置 FROZEN）。 */
    private static final String FREEZE_SQL = "UPDATE billing_bill SET status_before_freeze = status, "
            + "status = 'FROZEN' WHERE bill_no = ? AND status <> 'FROZEN'";
    private static final String RESTORE_SQL = "UPDATE billing_bill SET status = ?, "
            + "status_before_freeze = NULL WHERE bill_no = ? AND status = 'FROZEN'";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBillRepository(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(final Bill bill) {
        jdbcTemplate.update(INSERT_SQL, bill.billNo(), bill.billType().name(), bill.contractNo(),
                bill.requesterNo(), bill.providerNo(), bill.productId(), bill.productName(),
                bill.period(), bill.pricingModel().name(), bill.quantity(), bill.unitPrice(),
                bill.baseAmount(), bill.shareRate(), bill.amount(), bill.status().name(),
                bill.statusBeforeFreeze() == null ? null : bill.statusBeforeFreeze().name(),
                bill.correctionOfBillNo(), bill.createdBy(), Timestamp.valueOf(bill.createdAt()));
    }

    @Override
    public Optional<Bill> findByBillNo(final String billNo) {
        return jdbcTemplate.query(SELECT_BY_NO_SQL, this::mapBill, billNo).stream().findFirst();
    }

    @Override
    public Optional<Bill> findNormal(final String contractNo, final String period) {
        return jdbcTemplate.query(SELECT_NORMAL_SQL, this::mapBill, contractNo, period).stream()
                .findFirst();
    }

    @Override
    public List<Bill> findCorrectionsOf(final String originalBillNo) {
        return jdbcTemplate.query(SELECT_CORRECTIONS_SQL, this::mapBill, originalBillNo);
    }

    @Override
    public List<Bill> findByContractAndPeriod(final String contractNo, final String period) {
        return jdbcTemplate.query(SELECT_BY_CONTRACT_PERIOD_SQL, this::mapBill, contractNo, period);
    }

    @Override
    public List<Bill> findMine(final String subject, final BillSearchCriteria criteria,
            final PageQuery page) {
        final SqlArgs sqlArgs = new SqlArgs();
        sqlArgs.where.add("(requester_no = ? OR provider_no = ?)");
        sqlArgs.args.add(subject);
        sqlArgs.args.add(subject);
        applyCriteria(criteria, sqlArgs);
        sqlArgs.args.add(page.pageSize());
        sqlArgs.args.add(page.offset());
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM billing_bill" + sqlArgs.whereSql()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", this::mapBill,
                sqlArgs.args.toArray());
    }

    @Override
    public long countMine(final String subject, final BillSearchCriteria criteria) {
        final SqlArgs sqlArgs = new SqlArgs();
        sqlArgs.where.add("(requester_no = ? OR provider_no = ?)");
        sqlArgs.args.add(subject);
        sqlArgs.args.add(subject);
        applyCriteria(criteria, sqlArgs);
        return count(sqlArgs);
    }

    @Override
    public List<Bill> findGovernance(final BillSearchCriteria criteria, final PageQuery page) {
        final SqlArgs sqlArgs = new SqlArgs();
        applyCriteria(criteria, sqlArgs);
        sqlArgs.args.add(page.pageSize());
        sqlArgs.args.add(page.offset());
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM billing_bill" + sqlArgs.whereSql()
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", this::mapBill,
                sqlArgs.args.toArray());
    }

    @Override
    public long countGovernance(final BillSearchCriteria criteria) {
        final SqlArgs sqlArgs = new SqlArgs();
        applyCriteria(criteria, sqlArgs);
        return count(sqlArgs);
    }

    @Override
    public int markConfirmed(final String billNo) {
        return jdbcTemplate.update(CONFIRM_SQL, billNo);
    }

    @Override
    public int markFrozen(final String billNo) {
        return jdbcTemplate.update(FREEZE_SQL, billNo);
    }

    @Override
    public int markRestored(final String billNo, final BillStatus restoredStatus) {
        return jdbcTemplate.update(RESTORE_SQL, restoredStatus.name(), billNo);
    }

    private long count(final SqlArgs sqlArgs) {
        final Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill" + sqlArgs.whereSql(), Long.class,
                sqlArgs.args.toArray());
        return total == null ? 0L : total;
    }

    private static void applyCriteria(final BillSearchCriteria criteria, final SqlArgs sqlArgs) {
        if (criteria == null) {
            return;
        }
        if (criteria.contractNo() != null) {
            sqlArgs.where.add("contract_no = ?");
            sqlArgs.args.add(criteria.contractNo());
        }
        if (criteria.period() != null) {
            sqlArgs.where.add("`period` = ?");
            sqlArgs.args.add(criteria.period());
        }
        if (criteria.productId() != null) {
            sqlArgs.where.add("product_id = ?");
            sqlArgs.args.add(criteria.productId());
        }
        if (criteria.requesterNo() != null) {
            sqlArgs.where.add("requester_no = ?");
            sqlArgs.args.add(criteria.requesterNo());
        }
        if (criteria.providerNo() != null) {
            sqlArgs.where.add("provider_no = ?");
            sqlArgs.args.add(criteria.providerNo());
        }
        if (criteria.status() != null) {
            sqlArgs.where.add("status = ?");
            sqlArgs.args.add(criteria.status().name());
        }
        if (criteria.billType() != null) {
            sqlArgs.where.add("bill_type = ?");
            sqlArgs.args.add(criteria.billType().name());
        }
    }

    private Bill mapBill(final ResultSet rs, final int rowNum) throws SQLException {
        final String statusBeforeFreeze = rs.getString("status_before_freeze");
        return new Bill(rs.getLong("id"), rs.getString("bill_no"),
                BillType.valueOf(rs.getString("bill_type")), rs.getString("contract_no"),
                rs.getString("requester_no"), rs.getString("provider_no"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getString("period"),
                BillingPricingModel.fromSnapshot(rs.getString("pricing_model")),
                intOrNull(rs, "quantity"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("base_amount"), rs.getBigDecimal("share_rate"),
                rs.getBigDecimal("amount"), BillStatus.valueOf(rs.getString("status")),
                statusBeforeFreeze == null ? null : BillStatus.valueOf(statusBeforeFreeze),
                rs.getString("correction_of_bill_no"), rs.getString("created_by"),
                toLocalDateTime(rs.getTimestamp("created_at")));
    }

    private static Integer intOrNull(final ResultSet rs, final String column) throws SQLException {
        final int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
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
