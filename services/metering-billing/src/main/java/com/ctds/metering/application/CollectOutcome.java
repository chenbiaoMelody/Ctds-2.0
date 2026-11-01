package com.ctds.metering.application;

/** 采集编排结果（R-M0 响应载体；Q6-A 运营手动触发，Q8-A uk + upsert 幂等）。 */
public record CollectOutcome(long scannedTotal, long collectedTotal, SourceCounts bySource) {
}
