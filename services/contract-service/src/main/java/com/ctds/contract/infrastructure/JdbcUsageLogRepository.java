package com.ctds.contract.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsageLogEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行记录仓储（JdbcClient，迁移 V3 建表；hifi §7-2 事务口径）。两条写入路径传播刻意分离：
 * 拒绝留痕 REQUIRES_NEW（独立提交——拒绝留痕是拒绝的一部分，必须超越拒绝异常的回滚存活）；
 * 放行记录 REQUIRED（参与判定入口外层事务，与计数递增同事务提交）。
 */
@Repository
public class JdbcUsageLogRepository implements UsageLogRepository {

    private static final String LOG_COLUMNS = "contract_no, requester_no, action_type, outcome, "
            + "reason_code, violations, used_count, occurred_at";

    private final JdbcClient jdbc;

    public JdbcUsageLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertDenied(final UsageLogEntry entry) {
        insert(entry);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertAllowed(final UsageLogEntry entry) {
        insert(entry);
    }

    @Override
    public long countByOutcome(final String contractNo, final String outcome) {
        return jdbc.sql("SELECT COUNT(*) FROM contract_usage_log WHERE contract_no = ? "
                        + "AND outcome = ?")
                .param(contractNo).param(outcome).query(Long.class).single();
    }

    @Override
    public PageResult<UsageLogEntry> pageByContract(final String contractNo, final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM contract_usage_log WHERE contract_no = ?")
                .param(contractNo).query(Long.class).single();
        final List<Object> params = new ArrayList<>();
        params.add(contractNo);
        params.add(page.pageSize());
        params.add(page.offset());
        final List<UsageLogEntry> list = jdbc.sql("SELECT " + LOG_COLUMNS
                        + " FROM contract_usage_log WHERE contract_no = ? "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, rowNum) -> mapEntry(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private void insert(final UsageLogEntry entry) {
        jdbc.sql("INSERT INTO contract_usage_log (contract_no, requester_no, action_type, outcome, "
                        + "reason_code, violations, used_count, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .param(entry.contractNo()).param(entry.requesterNo())
                .param(entry.actionType().name()).param(entry.outcome().name())
                .param(entry.reasonCode()).param(entry.violations()).param(entry.usedCount())
                .param(entry.occurredAt()).update();
    }

    private static UsageLogEntry mapEntry(final ResultSet rs) throws SQLException {
        // wasNull 判定紧跟 getInt（中间不得夹读其它列——驱动语义：wasNull 反映最近一次读）
        final int usedCountRaw = rs.getInt("used_count");
        final Integer usedCount = rs.wasNull() ? null : usedCountRaw;
        return new UsageLogEntry(rs.getString("contract_no"), rs.getString("requester_no"),
                UsageActionType.valueOf(rs.getString("action_type")),
                UsageLogEntry.UsageOutcome.valueOf(rs.getString("outcome")),
                rs.getString("reason_code"), rs.getString("violations"), usedCount,
                rs.getTimestamp("occurred_at").toLocalDateTime());
    }
}
