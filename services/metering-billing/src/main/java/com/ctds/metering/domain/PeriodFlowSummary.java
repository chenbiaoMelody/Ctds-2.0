package com.ctds.metering.domain;

import java.time.LocalDateTime;

/** 合约 × 自然月聚合读数（B8-1 查询承载，Q2-A/Q3-A；period 形态 = YYYY-MM）。 */
public record PeriodFlowSummary(String period, long flowCount, LocalDateTime lastEventAt) {
}
