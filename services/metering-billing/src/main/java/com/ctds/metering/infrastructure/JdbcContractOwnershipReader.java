package com.ctds.metering.infrastructure;

import com.ctds.metering.config.MeteringProperties;
import com.ctds.metering.domain.ContractOwnership;
import com.ctds.metering.domain.ContractOwnershipReader;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合约归属实时读（hifi §2 查询 3）：ctds_contract.contract 双方字段 = 单一权威，
 * 流水表不冗余双方（B8-4 归属校验服务端硬保证的数据面）。
 */
@Repository
public class JdbcContractOwnershipReader implements ContractOwnershipReader {

    private static final String SQL =
            "SELECT contract_no, requester_subject_no, provider_subject_no FROM %s.contract "
                    + "WHERE contract_no = ?";

    private final JdbcTemplate jdbcTemplate;
    private final String contractSchema;

    public JdbcContractOwnershipReader(final JdbcTemplate jdbcTemplate,
            final MeteringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.contractSchema = properties.getSourceSchema().getContract();
    }

    @Override
    public Optional<ContractOwnership> findByContractNo(final String contractNo) {
        final List<ContractOwnership> rows = jdbcTemplate.query(String.format(SQL, contractSchema),
                (rs, rowNum) -> new ContractOwnership(rs.getString("contract_no"),
                        rs.getString("requester_subject_no"),
                        rs.getString("provider_subject_no")), contractNo);
        return rows.stream().findFirst();
    }
}
