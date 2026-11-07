package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.ReportNoGenerator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审计报告编号原子取号（WBS-3.8.4 hifi §2：EV-RPT + yyyyMMdd + 4 位当日序列；沿
 * JdbcEvidenceNoGenerator 同款写法——LAST_INSERT_ID 双路径保证连接级确定值，取号不回收）。
 * 与报告落库同事务（调用方 EvidenceReportRegistrar @Transactional 承载）。
 */
@Repository
public class JdbcReportNoGenerator implements ReportNoGenerator {

    private static final String BUMP_SQL =
            "INSERT INTO evidence_report_no_seq (seq_date, next_val) VALUES (?, LAST_INSERT_ID(1)) "
                    + "ON DUPLICATE KEY UPDATE next_val = LAST_INSERT_ID(next_val + 1)";
    private static final String LAST_SQL = "SELECT LAST_INSERT_ID()";
    /** seq_date = DATE 列（hifi §2 契约），按 LocalDate 存取（UTC 业务日）。 */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcClient jdbc;

    public JdbcReportNoGenerator(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String next(final LocalDate businessDate) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 取号与登记必须同事务（LAST_INSERT_ID 连接级语义；无事务 = 调用方契约破坏）
            throw new IllegalStateException("report no generation requires an active transaction");
        }
        jdbc.sql(BUMP_SQL).param(businessDate).update();
        final int seq = jdbc.sql(LAST_SQL).query(Integer.class).single();
        return "EV-RPT-" + SEQ_DATE.format(businessDate) + "-" + String.format("%04d", seq);
    }
}
