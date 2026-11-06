package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceRecord;

/**
 * 内部写面响应（hifi §1.1）：恒 200；上链失败以 {@code status=FAILED} 体现（fail-visible）。
 */
public record EvidenceCreateView(
        String evidenceNo,
        String stageType,
        String businessNo,
        String operationType,
        String status,
        String chainTxId,
        boolean duplicate,
        String occurredAt) {

    public static EvidenceCreateView from(final EvidenceRecord record, final boolean duplicate) {
        return new EvidenceCreateView(record.evidenceNo(), record.stageType().name(),
                record.businessNo(), record.operationType().name(), record.status().name(),
                record.chainTxId(), duplicate, EvidenceDigests.occurredAtIso(record.occurredAt()));
    }
}
