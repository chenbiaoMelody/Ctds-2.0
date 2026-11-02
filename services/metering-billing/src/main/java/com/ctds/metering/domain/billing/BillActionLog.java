package com.ctds.metering.domain.billing;

import java.time.LocalDateTime;

/**
 * 账单动作留痕（hifi §2 billing_bill_action_log）：创建 / 确认 / 冻结 / 解冻。
 * 只承载状态流转与业务可读原因，不承载金额或明细修改（B10-3）。
 */
public record BillActionLog(
        String billNo,
        BillingAction action,
        BillStatus fromStatus,
        BillStatus toStatus,
        String reason,
        String operatorSubjectNo,
        LocalDateTime createdAt) {
}
