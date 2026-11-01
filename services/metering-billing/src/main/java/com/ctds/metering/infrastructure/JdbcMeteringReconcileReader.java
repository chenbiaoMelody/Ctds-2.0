package com.ctds.metering.infrastructure;

import com.ctds.metering.config.MeteringProperties;
import com.ctds.metering.domain.MeteringReconcileReader;
import com.ctds.metering.domain.ReconcileCounts;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 勾稽读数只读腿（hifi §2 查询 4）：①delivery(FILE/SANDBOX_EXPORT, SUCCESS) 计数
 * ②usage_log(ALLOWED) 计数 ③counter.used_count（无行 = null）——四条只读零写零改。
 */
@Repository
public class JdbcMeteringReconcileReader implements MeteringReconcileReader {

    private static final String DELIVERY_COUNT_SQL =
            "SELECT COUNT(*) FROM %s.delivery WHERE contract_no = ? AND result = 'SUCCESS' "
                    + "AND delivery_type IN ('FILE', 'SANDBOX_EXPORT')";
    private static final String USAGE_LOG_COUNT_SQL =
            "SELECT COUNT(*) FROM %s.contract_usage_log WHERE contract_no = ? AND outcome = 'ALLOWED'";
    private static final String COUNTER_SQL =
            "SELECT used_count FROM %s.contract_usage_counter WHERE contract_no = ?";

    private final JdbcTemplate jdbcTemplate;
    private final String deliverySchema;
    private final String contractSchema;

    public JdbcMeteringReconcileReader(final JdbcTemplate jdbcTemplate,
            final MeteringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliverySchema = properties.getSourceSchema().getDelivery();
        this.contractSchema = properties.getSourceSchema().getContract();
    }

    @Override
    public ReconcileCounts readCounts(final String contractNo) {
        final Long deliveryCount = jdbcTemplate.queryForObject(
                String.format(DELIVERY_COUNT_SQL, deliverySchema), Long.class, contractNo);
        final Long usageLogCount = jdbcTemplate.queryForObject(
                String.format(USAGE_LOG_COUNT_SQL, contractSchema), Long.class, contractNo);
        final List<Integer> counter = jdbcTemplate.queryForList(
                String.format(COUNTER_SQL, contractSchema), Integer.class, contractNo);
        return new ReconcileCounts(deliveryCount == null ? 0L : deliveryCount,
                usageLogCount == null ? 0L : usageLogCount,
                counter.isEmpty() ? null : counter.get(0).longValue());
    }
}
