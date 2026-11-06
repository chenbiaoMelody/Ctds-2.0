package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 合约内部只读摘要（hifi §4.1；不含条款原文与数据本体）。
 * WBS-3.8.3 §3.4：新增 {@code requesterDid}（合约内部摘要扩展字段，缺失为 null——向后兼容），
 * 供交付环节存证取主体标识。
 */
public record ContractSummary(String contractNo, long productId, String productName, String applicantNo,
        String providerNo, String status, LocalDateTime effectiveAt, String requesterDid) {
}
