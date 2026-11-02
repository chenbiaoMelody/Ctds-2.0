package com.ctds.metering.domain.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账记录（hifi §2 billing_reconciliation）：每次执行一行，记四方数值、账单合计、
 * 复算金额、结论与差异明细 JSON；系统不自动修数（B11-4）。
 */
public record Reconciliation(
        long id,
        String contractNo,
        String period,
        int counterUsedCount,
        int usageLogAllowedCount,
        int meteringFlowCount,
        int usageLogAllowedAllTime,
        int meteringFlowAllTime,
        Integer billQuantity,
        BigDecimal billTotalAmount,
        BigDecimal recomputedAmount,
        ReconciliationConclusion conclusion,
        String detailsJson,
        String createdBy,
        LocalDateTime createdAt) {
}
