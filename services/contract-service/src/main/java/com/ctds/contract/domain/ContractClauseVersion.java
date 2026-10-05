package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 条款版本行（对应 contract_clause_version 表，hifi §4 表 2）：行级版本化、版本行不可变 =
 * 快照载体（修订永远新增行）。条款值/变更明细/规范化原文为 SM4 密文字节（CAT-04 L3，
 * common-crypto 唯一入口）；内容哈希为 SM3 不可逆摘要明文。规范化原文与哈希在双方确认齐
 * 锁定时写入（null = 未锁定）。
 */
public record ContractClauseVersion(Long id, long contractId, int versionNo,
        byte[] clauseValuesCipher, byte[] changesCipher, byte[] canonicalCipher,
        String contentHash, String proposedBy, LocalDateTime proposedAt,
        LocalDateTime confirmedProviderAt, LocalDateTime confirmedRequesterAt) {

    /** 本角色确认列时间（W7 条件更新的复查口径）。 */
    public LocalDateTime confirmedAt(final PartyRole role) {
        return role == PartyRole.PROVIDER ? confirmedProviderAt : confirmedRequesterAt;
    }

    /** 双方是否均已确认（锁定判据）。 */
    public boolean bothConfirmed() {
        return confirmedProviderAt != null && confirmedRequesterAt != null;
    }
}
