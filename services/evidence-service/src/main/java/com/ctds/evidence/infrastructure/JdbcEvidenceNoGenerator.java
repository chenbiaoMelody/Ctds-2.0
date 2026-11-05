package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.EvidenceNoGenerator;
import com.ctds.evidence.domain.EvidenceNos;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存证编号原子取号（hifi §2 / 前置检查 3：沿 subject `nextDailySeq` 同款写法）：
 * 首日插入写 {@code LAST_INSERT_ID(1)}（首次序号 = 1，且覆盖连接池残留值）；
 * 重复键走 {@code LAST_INSERT_ID(next_val + 1)}——两条路径均产生本连接确定值，
 * 自增与读取不跨语句竞态；取号只增不回收 → 编号终身不复用。
 */
@Repository
public class JdbcEvidenceNoGenerator implements EvidenceNoGenerator {

    private static final String BUMP_SQL =
            "INSERT INTO evidence_no_seq (seq_date, next_val) VALUES (?, LAST_INSERT_ID(1)) "
                    + "ON DUPLICATE KEY UPDATE next_val = LAST_INSERT_ID(next_val + 1)";
    private static final String LAST_SQL = "SELECT LAST_INSERT_ID()";
    /** seq_date = CHAR(8) 'yyyyMMdd'（hifi §2），按字符串存取，不走 java.sql.Date 隐式转换。 */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcClient jdbc;

    public JdbcEvidenceNoGenerator(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public String next(final LocalDate businessDate) {
        jdbc.sql(BUMP_SQL).param(SEQ_DATE.format(businessDate)).update();
        final int seq = jdbc.sql(LAST_SQL).query(Integer.class).single();
        return EvidenceNos.format(businessDate, seq);
    }
}
