package com.ctds.evidence.application;

/**
 * 内部写面入参（hifi §1.1：六字段全部必填，字符串原样入参由应用层校验后解析）。
 */
public record CreateEvidenceCommand(
        String stageType,
        String businessNo,
        String operationType,
        String subjectDid,
        String occurredAt,
        String actorSubjectNo) {
}
