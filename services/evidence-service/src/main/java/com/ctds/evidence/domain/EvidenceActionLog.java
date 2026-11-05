package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 状态流转与核验留痕（append-only；规格 B2-3 / B3-3：人 / 时点 / 对象 / 结论）。
 *
 * @param reasonCode 1012 段码值或 {@code NONE}（核验 / 成功路径固定 NONE）
 * @param detail     结论或原因摘要，禁原文与个人信息（≤512）
 */
public record EvidenceActionLog(
        long id,
        String evidenceNo,
        EvidenceAction action,
        EvidenceStatus fromStatus,
        EvidenceStatus toStatus,
        String actorSubjectNo,
        String reasonCode,
        String detail,
        Instant createdAt) {

    /** 新建留痕（id 由库生成，占位 0）。 */
    public static EvidenceActionLog of(final String evidenceNo, final EvidenceAction action,
            final EvidenceStatus fromStatus, final EvidenceStatus toStatus,
            final String actorSubjectNo, final String reasonCode, final String detail,
            final Instant now) {
        return new EvidenceActionLog(0L, evidenceNo, action, fromStatus, toStatus,
                actorSubjectNo, reasonCode, detail, now);
    }
}
