package com.ctds.metering.domain.billing;

/**
 * 计费档位（与目录域定价四档对齐的快照枚举，WBS-3.7.6 hifi §3.2）：
 * 免费 / 按次 / 包月 / 交易额分成。快照值来自 ctds_contract.contract.pricing_model 字符串列。
 */
public enum BillingPricingModel {

    /** 免费档：不产生计费流水与账单（B8-2）。 */
    FREE,
    /** 按次：本期计量事件数 × 次单价（B9-1）。 */
    PER_CALL,
    /** 包月：订单周期费整月（B9-2，不按天折算）。 */
    MONTHLY,
    /** 交易额分成：约定交易额 × 分成费率（B9-3，费率 = price_amount 百分比 0~100）。 */
    REVENUE_SHARE;

    /** 快照字符串解析（未知档位 = 上游数据异常，返回 null 由调用方 fail-closed）。 */
    public static BillingPricingModel fromSnapshot(final String value) {
        if (value == null) {
            return null;
        }
        for (final BillingPricingModel model : values()) {
            if (model.name().equals(value)) {
                return model;
            }
        }
        return null;
    }
}
