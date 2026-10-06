package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 存证事件行（对应 contract_attestation 表，hifi §4 表 4）：合约哈希 + 双方签署要素；
 * V1.0 落库留痕，链上对接归 3.8.x（预留埋点口径 = 本行事件 + 合约哈希，3.8.3 消费）。
 */
public record ContractAttestation(Long id, long contractId, String contractNo, String contentHash,
        String providerSubjectNo, String providerDid, LocalDateTime providerSignedAt,
        String requesterSubjectNo, String requesterDid, LocalDateTime requesterSignedAt,
        LocalDateTime attestedAt) {
}
