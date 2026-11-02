package com.ctds.metering.infrastructure.billing;

import com.ctds.metering.config.MeteringProperties;
import com.ctds.metering.domain.billing.BillingContractReader;
import com.ctds.metering.domain.billing.BillingPricingModel;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合约计费快照只读实现（hifi §3.1）：跨 schema SELECT ctds_contract.contract，
 * 零写零改上游；库名前缀沿用 ctds.metering.source-schema.contract（配置化标识符已 fail-fast 校验）。
 */
@Repository
public class JdbcBillingContractReader implements BillingContractReader {

    private static final String COLUMNS =
            "contract_no, requester_subject_no, provider_subject_no, product_id, product_name, "
                    + "pricing_model, price_amount, status, effective_at, ended_at";
    private static final String BY_NO_SQL =
            "SELECT " + COLUMNS + " FROM %s.contract WHERE contract_no = ?";
    private static final String BILLABLE_SQL =
            "SELECT " + COLUMNS + " FROM %s.contract WHERE pricing_model <> 'FREE' "
                    + "AND effective_at IS NOT NULL AND effective_at <= ? "
                    + "AND (ended_at IS NULL OR ended_at > ?) ORDER BY id";

    private final JdbcTemplate jdbcTemplate;
    private final String contractSchema;

    public JdbcBillingContractReader(final JdbcTemplate jdbcTemplate,
            final MeteringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.contractSchema = properties.getSourceSchema().getContract();
    }

    @Override
    public Optional<ContractPricingSnapshot> findByContractNo(final String contractNo) {
        final List<ContractPricingSnapshot> rows = jdbcTemplate.query(
                String.format(BY_NO_SQL, contractSchema), this::mapRow, contractNo);
        return rows.stream().findFirst();
    }

    @Override
    public List<ContractPricingSnapshot> findBillable(final LocalDateTime periodStart,
            final LocalDateTime periodEnd) {
        return jdbcTemplate.query(String.format(BILLABLE_SQL, contractSchema), this::mapRow,
                Timestamp.valueOf(periodEnd), Timestamp.valueOf(periodStart));
    }

    private ContractPricingSnapshot mapRow(final ResultSet rs, final int rowNum) throws SQLException {
        return new ContractPricingSnapshot(rs.getString("contract_no"),
                rs.getString("requester_subject_no"), rs.getString("provider_subject_no"),
                rs.getLong("product_id"), rs.getString("product_name"),
                BillingPricingModel.fromSnapshot(rs.getString("pricing_model")),
                rs.getBigDecimal("price_amount"), rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("effective_at")),
                toLocalDateTime(rs.getTimestamp("ended_at")));
    }

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
