package com.ctds.contract.infrastructure;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用计数仓储（JdbcClient，迁移 V3 建表；hifi §7-1 判检一体口径）。
 * 事务边界在本仓储方法（@Transactional，REQUIRED 参与判定入口外层事务——放行腿"递增 +
 * 放行记录"同事务提交，耗尽腿行初始化随外层回滚，数据库零副作用；沿
 * JdbcContractRepository/JdbcContractTemplateRepository 先例）。
 */
@Repository
public class JdbcUsageCounterStore implements UsageCounterStore {

    private final JdbcClient jdbc;

    public JdbcUsageCounterStore(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Integer tryIncrement(final String contractNo, final int limit,
            final LocalDateTime usedAt) {
        // ① 行初始化（幂等）：no-op upsert 而非 INSERT IGNORE——INSERT IGNORE 的重复键路径加
        // 共享锁，与本事务随后的条件 UPDATE（需排他锁）形成 S→X 锁升级，并发首用可死锁
        // （实测 CannotAcquireLockException）；no-op upsert 走排他锁路径，同合约并发在本行
        // 串行排队（无循环等待 → 无死锁）。耗尽腿该行初始化随外层回滚撤销（数据库零副作用）。
        jdbc.sql("INSERT INTO contract_usage_counter (contract_no, used_count, last_used_at) "
                        + "VALUES (?, 0, NULL) ON DUPLICATE KEY UPDATE used_count = used_count")
                .param(contractNo).update();
        // ② 判检一体：单条条件 UPDATE（数据库单语句原子性——并发"第 N 次"只有一方影响行数 = 1；
        // 先查后增的替代实现实测超卖 1 次，见 build-output/w345-concurrency-oversell-*.txt）
        final int updated = jdbc.sql("UPDATE contract_usage_counter SET used_count = used_count + 1, "
                        + "last_used_at = ? WHERE contract_no = ? AND used_count < ?")
                .param(usedAt).param(contractNo).param(limit).update();
        if (updated == 0) {
            // 影响行数 = 0 = 耗尽（计数零变化；拒绝留痕由应用层独立事务写入）
            return null;
        }
        return jdbc.sql("SELECT used_count FROM contract_usage_counter WHERE contract_no = ?")
                .param(contractNo).query(Integer.class).single();
    }

    @Override
    public int currentCount(final String contractNo) {
        return jdbc.sql("SELECT used_count FROM contract_usage_counter WHERE contract_no = ?")
                .param(contractNo).query(Integer.class).optional().orElse(0);
    }
}
