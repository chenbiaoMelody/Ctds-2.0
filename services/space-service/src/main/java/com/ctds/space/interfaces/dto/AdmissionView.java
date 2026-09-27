package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.SpaceAdmission;
import java.time.LocalDateTime;

/**
 * 准入单视图（WBS-3.2.4 hifi §1 端点 1~6 出参；memberId 仅 APPROVED 单非空——贯通追溯成员行）。
 */
public record AdmissionView(
        Long id,
        Long spaceId,
        String subjectNo,
        String type,
        String status,
        String operator,
        String reason,
        Long memberId,
        LocalDateTime createdAt) {

    public static AdmissionView from(final SpaceAdmission admission) {
        return new AdmissionView(admission.id(), admission.spaceId(), admission.subjectNo(),
                admission.type().name(), admission.status().name(), admission.operator(), admission.reason(),
                admission.memberId(), admission.createdAt());
    }
}
