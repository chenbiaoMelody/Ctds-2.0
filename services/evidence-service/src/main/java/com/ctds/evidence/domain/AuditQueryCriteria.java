package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 监管审计读面查询条件（WBS-3.8.4 hifi §1 E2~E4 共用）：环节 / 单据号 / 主体 DID 摘要 / 时间窗。
 * 与 3.8.2 {@link EvidenceSearchCriteria} 的差异 = 增加主体摘要过滤（Q2-A DID 摘要等值），
 * 不改既有清单条件契约。
 */
public record AuditQueryCriteria(EvidenceStageType stageType, String businessNo,
        String subjectDigest, Instant from, Instant to) {

    public boolean isEmpty() {
        return stageType == null && businessNo == null && subjectDigest == null
                && from == null && to == null;
    }
}
