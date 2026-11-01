package com.ctds.metering.application;

/** 分源采集计数（R-M0 响应 bySource 三组；hifi §1）。 */
public record SourceCounts(ScannedCollected fileDelivery, ScannedCollected sandboxExport,
        ScannedCollected sandboxJob) {
}
