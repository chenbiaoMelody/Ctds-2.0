package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceRecord;

/**
 * 存证记录读面（六要素 + 内容摘要 + 状态 + 链上标识 + 重试/失败信息）。
 */
public record EvidenceRecordView(
        String evidenceNo,
        String stageType,
        String businessNo,
        String operationType,
        String subjectDidDigest,
        String occurredAt,
        String contentDigest,
        String status,
        String chainTxId,
        Long chainBlockNo,
        int retryCount,
        String lastErrorSummary,
        String createdAt,
        String updatedAt) {

    public static EvidenceRecordView from(final EvidenceRecord record) {
        return new EvidenceRecordView(record.evidenceNo(), record.stageType().name(),
                record.businessNo(), record.operationType().name(), record.subjectDidDigest(),
                EvidenceDigests.occurredAtIso(record.occurredAt()), record.contentDigest(),
                record.status().name(), record.chainTxId(), record.chainBlockNo(),
                record.retryCount(), record.lastErrorSummary(),
                EvidenceDigests.occurredAtIso(record.createdAt()),
                EvidenceDigests.occurredAtIso(record.updatedAt()));
    }
}
