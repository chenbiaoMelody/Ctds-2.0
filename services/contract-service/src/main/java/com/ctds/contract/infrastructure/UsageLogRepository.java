package com.ctds.contract.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.policy.UsageLogEntry;

/**
 * 执行记录仓储端口（WBS-3.4.5 hifi §1 / §7-2）：放行记录与拒绝留痕写入 + R12 查询。
 * 两条写入路径的事务传播刻意分离（hifi §7-2 事务口径）：放行记录与计数递增同事务提交；
 * 拒绝留痕必须超越拒绝异常的外层回滚独立提交（拒绝腿事务内只有插入 log——计数零变化由
 * "判定步 9 之前不触碰 counter"结构性保证）。
 */
public interface UsageLogRepository {

    /**
     * 拒绝留痕写入（独立事务 REQUIRES_NEW——拒绝留痕是拒绝的一部分，必须在拒绝异常
     * 抛出后存活；含触发要素明细与当前不变计数）。
     */
    void insertDenied(UsageLogEntry entry);

    /**
     * 放行记录写入（REQUIRED——参与判定入口外层事务，与计数递增同事务提交）。
     */
    void insertAllowed(UsageLogEntry entry);

    /**
     * R12 摘要计数：指定合约按结果（ALLOWED / DENIED）统计行数。
     */
    long countByOutcome(String contractNo, String outcome);

    /**
     * R12 记录分页（按 id 倒序——最新在前；idx_usage_log_contract 索引前缀）。
     *
     * @param page 分页参数（沿合约域既有分页口径）
     */
    PageResult<UsageLogEntry> pageByContract(String contractNo, PageQuery page);
}
