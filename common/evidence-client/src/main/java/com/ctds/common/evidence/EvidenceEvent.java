package com.ctds.common.evidence;

import java.time.Instant;

/**
 * 存证事件（WBS-3.8.3 hifi §1.3 发送契约：固定六字段，无附加字段）。
 * 值域与业务语义由存证侧守卫（规格 B1-1 / B1-2），客户端不做二次校验与改写。
 *
 * @param stageType      环节（AUTHORIZATION / CONTRACT / DELIVERY / USAGE）
 * @param businessNo     业务单据号（与上游逐字一致；授权 / 使用按 Q2 / Q3 派生）
 * @param operationType  操作类型（六类封闭枚举，与环节强绑定）
 * @param subjectDid     主体 DID（did:ctds: 开头；取不到即跳过，不伪造）
 * @param occurredAt     事件时点（UTC）
 * @param actorSubjectNo 操作人主体编号
 */
public record EvidenceEvent(
        String stageType,
        String businessNo,
        String operationType,
        String subjectDid,
        Instant occurredAt,
        String actorSubjectNo) {
}
