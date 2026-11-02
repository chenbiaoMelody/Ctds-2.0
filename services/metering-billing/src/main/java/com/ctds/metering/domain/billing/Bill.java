package com.ctds.metering.domain.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账单主表行（hifi §2 billing_bill）：金额与明细生成后不可修改；状态仅由确认 / 冻结 / 解冻流转。
 * 全部计费要素（双方 / 产品 / 定价档 / 单价 / 基数 / 费率）为出账时点上游快照，保证可复算与不追溯。
 */
public record Bill(
        long id,
        String billNo,
        BillType billType,
        String contractNo,
        String requesterNo,
        String providerNo,
        long productId,
        String productName,
        String period,
        BillingPricingModel pricingModel,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal baseAmount,
        BigDecimal shareRate,
        BigDecimal amount,
        BillStatus status,
        BillStatus statusBeforeFreeze,
        String correctionOfBillNo,
        String createdBy,
        LocalDateTime createdAt) {
}
