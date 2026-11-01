package com.ctds.metering.application;

import java.time.LocalDateTime;

/** 合约 × 自然月聚合条目（R-M2/R-M3 响应；period 降序）。 */
public record PeriodSummary(String period, long flowCount, LocalDateTime lastEventAt) {
}
