package com.ctds.contract.domain.policy;

import java.time.LocalDateTime;

/**
 * 执行记录行值对象（WBS-3.4.5 hifi §5 V3 {@code contract_usage_log} 行载体）：留痕四要素
 * 口径（谁/何时/哪份合约/动作类型/触发要素/结果）——不含条款原文与请求文本原文，
 * {@code violations} 只落枚举名（逗号分隔），规避 L3 分级纠缠。
 *
 * @param contractNo  合约编号
 * @param requesterNo 发起主体号（谁）
 * @param actionType  动作类型（USE / REDISTRIBUTE）
 * @param outcome     结果（ALLOWED / DENIED）
 * @param reasonCode  拒绝原因码尾号（C0020 / C0013；ALLOWED 时为 null）
 * @param violations  触发要素码（枚举名逗号分隔；ALLOWED 时为 null）
 * @param usedCount   ALLOWED = 递增后计数；DENIED = 当前不变值
 * @param occurredAt  判定时点（注入 Clock）
 */
public record UsageLogEntry(String contractNo, String requesterNo, UsageActionType actionType,
        UsageOutcome outcome, String reasonCode, String violations, Integer usedCount,
        LocalDateTime occurredAt) {

    /** 执行结果枚举（DDL outcome 列口径，VARCHAR(8)）。 */
    public enum UsageOutcome {

        /** 放行。 */
        ALLOWED,
        /** 拒绝。 */
        DENIED
    }
}
