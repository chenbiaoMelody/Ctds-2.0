package com.ctds.metering.infrastructure;

import com.ctds.metering.config.MeteringProperties;
import com.ctds.metering.domain.MeteringSourceType;
import com.ctds.metering.domain.MeteringSourceReader;
import com.ctds.metering.domain.SourceRow;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 事件源跨 schema 只读采集（Q1-A 通道 2，hifi §2 采集查询契约）：只取 SUCCESS/SUCCEEDED 侧
 * = 拒绝零计量结构性保证；交付腿排除 API 行（API 计量源唯一 = 网关事件，B7-3 不双计）。
 * 库名为配置化常量（绑定期已校验标识符形态——无运行时注入面）。
 */
@Repository
public class JdbcMeteringSourceReader implements MeteringSourceReader {

    private static final String DELIVERY_SQL =
            "SELECT delivery_no, contract_no, requester_no, delivery_type, created_at FROM %s.delivery "
                    + "WHERE result = 'SUCCESS' AND delivery_type IN ('FILE', 'SANDBOX_EXPORT')";
    private static final String SANDBOX_JOB_SQL =
            "SELECT j.job_no, t.contract_no, t.requester_no, j.created_at FROM %s.sandbox_job j "
                    + "JOIN %s.sandbox_task t ON j.sandbox_no = t.sandbox_no WHERE j.status = 'SUCCEEDED'";

    private final JdbcTemplate jdbcTemplate;
    private final String deliverySchema;
    private final String contractSchema;

    public JdbcMeteringSourceReader(final JdbcTemplate jdbcTemplate,
            final MeteringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliverySchema = properties.getSourceSchema().getDelivery();
        this.contractSchema = properties.getSourceSchema().getContract();
    }

    @Override
    public List<SourceRow> readDeliveryRows() {
        return jdbcTemplate.query(String.format(DELIVERY_SQL, deliverySchema),
                (rs, rowNum) -> new SourceRow(rs.getString("delivery_no"),
                        rs.getString("contract_no"), rs.getString("requester_no"),
                        toSourceType(rs.getString("delivery_type")),
                        toLocalDateTime(rs.getTimestamp("created_at"))));
    }

    @Override
    public List<SourceRow> readSandboxJobRows() {
        final String sql = String.format(SANDBOX_JOB_SQL, deliverySchema, deliverySchema);
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new SourceRow(rs.getString("job_no"), rs.getString("contract_no"),
                        rs.getString("requester_no"), MeteringSourceType.SANDBOX_JOB,
                        toLocalDateTime(rs.getTimestamp("created_at"))));
    }

    private static MeteringSourceType toSourceType(final String deliveryType) {
        return switch (deliveryType) {
            case "FILE" -> MeteringSourceType.FILE_DELIVERY;
            case "SANDBOX_EXPORT" -> MeteringSourceType.SANDBOX_EXPORT;
            default -> throw new IllegalStateException("未知交付类型：" + deliveryType);
        };
    }

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
