package com.ctds.contract.interfaces.dto;

import java.time.LocalDateTime;

/**
 * 内部只读合约摘要出站视图（WBS-3.5.3 hifi §4.1；ADR-016 §6 第 8 消费方 = delivery-service）：
 * 仅供交付服务做准入/形态校验，不含条款原文与数据本体。
 */
public final class InternalContractSummaryViews {

    private InternalContractSummaryViews() {
    }

    /**
     * 内部摘要（WBS-3.8.3 §3.4：新增 {@code requesterDid} 供交付环节存证取主体标识；
     * 只增字段、不改既有字段名与语义，缺失时为 null——向后兼容）。
     */
    public record Summary(String contractNo, long productId, String productName, String applicantNo,
            String providerNo, String status, LocalDateTime effectiveAt, String requesterDid) {
    }
}
