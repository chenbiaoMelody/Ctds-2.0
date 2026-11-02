package com.ctds.metering.application.billing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单详情出站视图（hifi §3.4）：账单头 + 金额构成快照 + 明细 + 动作时间线 + 更正单号列表
 * （更正关系双向可溯：原单可见其更正单，更正单可见原单号）。
 */
public record BillDetailView(
        String billNo,
        String billType,
        String contractNo,
        String requesterNo,
        String providerNo,
        long productId,
        String productName,
        String period,
        String pricingModel,
        Integer quantity,
        String unitPrice,
        String baseAmount,
        String shareRate,
        String amount,
        String status,
        String correctionOfBillNo,
        LocalDateTime createdAt,
        List<BillDetailLineView> details,
        List<BillActionView> actions,
        List<String> correctionBillNos) {
}
