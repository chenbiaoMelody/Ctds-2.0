package com.ctds.metering.domain;

/**
 * 四方勾稽读数之事件源三腿（①交付文件腿 ②策略判定腿 ③余额口径）。
 * 纯读数展示，无差异处置语义（B11 对账动作归 3.7.6/3.7.8——hifi §1 R-M3 越界声明）。
 */
public record ReconcileCounts(long deliveryFileCount, long usageLogAllowedCount,
        Long usageCounterUsedCount) {
}
