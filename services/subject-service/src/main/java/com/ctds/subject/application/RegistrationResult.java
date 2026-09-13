package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;

/** 注册/重报结果（应用层出参；申请编号为后续认证、审核、查询的统一跟踪标识）。 */
public record RegistrationResult(String subjectNo, SubjectStatus status) {
}
