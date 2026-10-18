package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/** 合约内部只读摘要（hifi §4.1；不含条款原文与数据本体）。 */
public record ContractSummary(String contractNo, long productId, String productName, String applicantNo,
        String providerNo, String status, LocalDateTime effectiveAt) {
}
