package com.ctds.contract.interfaces.dto;

import java.time.LocalDateTime;

/**
 * 内部只读合约摘要出站视图（WBS-3.5.3 hifi §4.1；ADR-016 §6 第 8 消费方 = delivery-service）：
 * 仅供交付服务做准入/形态校验，不含条款原文与数据本体。
 */
public final class InternalContractSummaryViews {

    private InternalContractSummaryViews() {
    }

    public record Summary(String contractNo, long productId, String productName, String applicantNo,
            String providerNo, String status, LocalDateTime effectiveAt) {
    }
}
