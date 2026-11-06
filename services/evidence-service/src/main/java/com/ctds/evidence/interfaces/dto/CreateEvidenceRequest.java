package com.ctds.evidence.interfaces.dto;

import com.ctds.evidence.application.CreateEvidenceCommand;

/**
 * 内部写面请求体（hifi §1.1：六字段全部必填；字符串原样接收，校验与解析在应用层完成）。
 */
public record CreateEvidenceRequest(
        String stageType,
        String businessNo,
        String operationType,
        String subjectDid,
        String occurredAt,
        String actorSubjectNo) {

    public CreateEvidenceCommand toCommand() {
        return new CreateEvidenceCommand(stageType, businessNo, operationType, subjectDid,
                occurredAt, actorSubjectNo);
    }
}
