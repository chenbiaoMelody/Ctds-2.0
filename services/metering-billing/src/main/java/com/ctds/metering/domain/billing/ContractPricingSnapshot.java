package com.ctds.metering.domain.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合约计费快照（只读自 ctds_contract.contract，hifi §3.1）：双方 / 产品 / 定价档 / 定价数值 /
 * 生效与终止时点。计费全部以此快照为准，账单落快照后不再回读上游（可复算 + 不追溯，B9-3/B9-4）。
 */
public record ContractPricingSnapshot(
        String contractNo,
        String requesterNo,
        String providerNo,
        long productId,
        String productName,
        BillingPricingModel pricingModel,
        BigDecimal priceAmount,
        String status,
        LocalDateTime effectiveAt,
        LocalDateTime endedAt) {
}
