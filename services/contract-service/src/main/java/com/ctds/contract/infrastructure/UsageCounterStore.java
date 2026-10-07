package com.ctds.contract.infrastructure;

import java.time.LocalDateTime;

/**
 * 使用计数仓储端口（WBS-3.4.5 hifi §1 / §7-1）：配额判检一体的原子递增与计数读取。
 * 实现类事务传播 = REQUIRED（参与判定入口外层事务——放行腿"递增 + 放行记录"同事务提交，
 * hifi §7-2；耗尽腿条件 UPDATE 未命中时行初始化随外层回滚，数据库零副作用）。
 */
public interface UsageCounterStore {

    /**
     * 判检一体原子递增（hifi §3 步 9）：行初始化（INSERT IGNORE，幂等）+ 条件 UPDATE
     * {@code SET used_count = used_count + 1, last_used_at = ? WHERE contract_no = ? AND
     * used_count < ?}——数据库单语句原子性防"先查后增"竞态超卖，无应用层锁。
     *
     * @param contractNo 合约编号
     * @param limit      次数上限（策略 quota.maxCount）
     * @param usedAt     判定时点（last_used_at 落值）
     * @return 递增后已用次数；耗尽（影响行数 = 0）返回 null——计数零变化
     */
    Integer tryIncrement(String contractNo, int limit, LocalDateTime usedAt);

    /**
     * 当前计数读取（R12 摘要与拒绝留痕 usedCount 口径；行不存在 = 0）。
     */
    int currentCount(String contractNo);
}
