package com.ctds.metering.domain.billing;

/**
 * 对账四方数值（hifi §5-2）：counter 余额口径（无行 = null，该腿跳过）/
 * 全量 ALLOWED 流水 / 全量计量流水 / 本期 ALLOWED 流水 / 本期计量流水。
 * 两个"本期"数值按事件时点（occurred_at）切自然月，与计量 R-M2 同口径。
 */
public record BillingUsageMetrics(
        Integer counterUsedCount,
        int usageLogAllowedPeriod,
        int usageLogAllowedAllTime,
        int meteringFlowPeriod,
        int meteringFlowAllTime) {
}
