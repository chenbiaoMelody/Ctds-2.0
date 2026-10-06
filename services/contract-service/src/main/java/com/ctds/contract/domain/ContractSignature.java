package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 签署记录行（对应 contract_signature 表，hifi §4 表 3）：每方一行不可变（uk_contract_party）。
 * 签名值为 SM2 DER Base64 的 SM4 密文（CAT-04 L3）；所签内容哈希 = 锁定版本 content_hash
 * （不可逆摘要明文）。出站只含 DID 与签署时间，签名值不出站（hifi §2.2 R7 字段集锚定）。
 */
public record ContractSignature(Long id, long contractId, String contractNo, PartyRole partyRole,
        String subjectNo, String did, String contentHash, byte[] signatureCipher,
        LocalDateTime signedAt, LocalDateTime createdAt) {
}
