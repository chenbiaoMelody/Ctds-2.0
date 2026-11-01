package com.ctds.metering.application;

import java.util.List;

/**
 * 四方勾稽读数（R-M3 响应）：①交付文件腿 ②策略判定腿（usage_log ALLOWED）③余额口径
 * （counter.used_count）④计量流水计数 + 分期聚合。纯读数展示，无差异处置语义
 * （B11 对账动作归 3.7.6/3.7.8——hifi §1 越界声明）。
 */
public record ReconcileSnapshot(String contractNo, long deliveryFileCount,
        long usageLogAllowedCount, Long usageCounterUsedCount, long meteringFlowCount,
        List<PeriodSummary> meteringFlowByPeriod) {
}
