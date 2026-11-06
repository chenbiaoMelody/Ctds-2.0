package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceDigests;

/**
 * 状态流转 / 核验留痕读面（人 / 时点 / 对象 / 结论）。
 */
public record EvidenceActionView(
        String action,
        String fromStatus,
        String toStatus,
        String actorSubjectNo,
        String reasonCode,
        String detail,
        String createdAt) {

    public static EvidenceActionView from(final EvidenceActionLog action) {
        return new EvidenceActionView(action.action().name(),
                action.fromStatus() == null ? null : action.fromStatus().name(),
                action.toStatus() == null ? null : action.toStatus().name(),
                action.actorSubjectNo(), action.reasonCode(), action.detail(),
                EvidenceDigests.occurredAtIso(action.createdAt()));
    }
}
