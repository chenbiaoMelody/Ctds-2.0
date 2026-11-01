package com.ctds.metering.domain;

/** 勾稽读数端口（只读 SELECT 计数，零写零改——hifi §2 查询 4）。 */
public interface MeteringReconcileReader {

    /** ①交付文件腿 ②策略判定腿（usage_log ALLOWED）③余额口径（counter.used_count，无行 = null）。 */
    ReconcileCounts readCounts(String contractNo);
}
