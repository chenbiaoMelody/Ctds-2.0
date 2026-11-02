package com.ctds.metering.domain.billing;

/**
 * 账单动作留痕类型（hifi §2 billing_bill_action_log.action）：
 * 创建 / 确认 / 冻结 / 解冻。留痕表不承载金额或明细修改（B10-3 不可变）。
 */
public enum BillingAction {

    /** 创建（手动出账 / 自动出账 / 更正单）。 */
    CREATE,
    /** 运营确认。 */
    CONFIRM,
    /** 对账差异冻结。 */
    FREEZE,
    /** 对账通过解冻。 */
    UNFREEZE
}
