package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 存证记录（规格 §2 术语"存证记录"：六要素 + 内容摘要 + 状态 + 链上标识）。
 *
 * <p>不可变值对象：状态推进通过 {@link #onChain} / {@link #failed} / {@link #retryFailed}
 * 生成新副本；内容列（编号 / 环节 / 单据号 / 操作类型 / DID 摘要 / 事件时点 / 内容摘要 /
 * 创建时点）一经生成不提供任何改写入口（规格 B2-4）。</p>
 */
public record EvidenceRecord(
        long id,
        String evidenceNo,
        EvidenceStageType stageType,
        String businessNo,
        EvidenceOperationType operationType,
        String subjectDidDigest,
        Instant occurredAt,
        String contentDigest,
        EvidenceStatus status,
        String chainTxId,
        Long chainBlockNo,
        int retryCount,
        String lastErrorSummary,
        Instant createdAt,
        Instant updatedAt) {

    /** 新建待上链记录（id 由库生成，占位 0）。 */
    public static EvidenceRecord pending(final String evidenceNo, final EvidenceStageType stageType,
            final String businessNo, final EvidenceOperationType operationType,
            final String subjectDidDigest, final Instant occurredAt, final String contentDigest,
            final Instant now) {
        return new EvidenceRecord(0L, evidenceNo, stageType, businessNo, operationType,
                subjectDidDigest, occurredAt, contentDigest, EvidenceStatus.PENDING, null, null,
                0, null, now, now);
    }

    /** 上链成功：状态推进为 ON_CHAIN（重试路径 retryCount 由调用方 +1）。 */
    public EvidenceRecord onChain(final String txId, final long blockNo, final int nextRetryCount,
            final Instant now) {
        return new EvidenceRecord(id, evidenceNo, stageType, businessNo, operationType,
                subjectDidDigest, occurredAt, contentDigest, EvidenceStatus.ON_CHAIN, txId, blockNo,
                nextRetryCount, null, createdAt, now);
    }

    /** 上链失败：状态 FAILED + 脱敏原因摘要（重试次数不变）。 */
    public EvidenceRecord failed(final String errorSummary, final Instant now) {
        return new EvidenceRecord(id, evidenceNo, stageType, businessNo, operationType,
                subjectDidDigest, occurredAt, contentDigest, EvidenceStatus.FAILED, null, null,
                retryCount, errorSummary, createdAt, now);
    }

    /** 重试失败：保持 FAILED / PENDING 记录转为 FAILED，重试次数 +1，刷新原因摘要。 */
    public EvidenceRecord retryFailed(final String errorSummary, final Instant now) {
        return new EvidenceRecord(id, evidenceNo, stageType, businessNo, operationType,
                subjectDidDigest, occurredAt, contentDigest, EvidenceStatus.FAILED, chainTxId,
                chainBlockNo, retryCount + 1, errorSummary, createdAt, now);
    }
}
