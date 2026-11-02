package com.ctds.metering.infrastructure.billing;

import com.ctds.metering.domain.billing.BillNoGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账单号原子取号（hifi §2，沿 contract_no_seq 单行取号先例）：
 * 单行 UPDATE 用 LAST_INSERT_ID(expr) 原子自增，再在同一连接上读回；
 * 必须在出账事务内调用（事务连接唯一，防跨连接读到他人序号）。
 */
@Repository
public class JdbcBillNoGenerator implements BillNoGenerator {

    private static final String BUMP_SQL =
            "UPDATE billing_bill_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1";
    private static final String LAST_SQL = "SELECT LAST_INSERT_ID()";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBillNoGenerator(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String nextBillNo() {
        jdbcTemplate.update(BUMP_SQL);
        final Long value = jdbcTemplate.queryForObject(LAST_SQL, Long.class);
        final long no = value == null ? 1L : value;
        return String.format("BL%06d", no);
    }
}
