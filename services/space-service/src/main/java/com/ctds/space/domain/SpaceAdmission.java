package com.ctds.space.domain;

import java.time.LocalDateTime;

/**
 * 空间准入单（一行 = 一次准入流程；WBS-3.2.2 hifi §1.4，逐列对应 space_admission 表）。
 *
 * <p>未确认邀请与未审批申请不产生成员关系——仅 status=APPROVED 的准入单有 memberId；
 * 状态机流转细则归 3.2.4。</p>
 */
public record SpaceAdmission(
        Long id,
        Long spaceId,
        String subjectNo,
        AdmissionType type,
        AdmissionStatus status,
        String operator,
        String reason,
        Long memberId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
