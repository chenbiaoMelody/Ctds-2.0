package com.ctds.metering.application.billing;

import java.time.LocalDateTime;

/**
 * 账单摘要出站视图（hifi §3.4 B-R1/B-R3）：金额一律字符串（toPlainString，防浮点与科学计数）；
 * myRole 仅本方读面返回（REQUESTER / PROVIDER）。
 */
public record BillSummary(
        String billNo,
        String billType,
        String contractNo,
        String requesterNo,
        String providerNo,
        long productId,
        String productName,
        String period,
        String pricingModel,
        String amount,
        String status,
        String correctionOfBillNo,
        LocalDateTime createdAt,
        String myRole) {
}
