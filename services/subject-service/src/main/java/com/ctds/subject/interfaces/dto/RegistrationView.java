package com.ctds.subject.interfaces.dto;

import com.ctds.subject.application.RegistrationResult;

/** 注册结果视图（申请编号为后续认证、审核、查询的统一跟踪标识，规格行为 1 第 4 条）。 */
public record RegistrationView(String subjectNo, String status) {

    public static RegistrationView from(final RegistrationResult result) {
        return new RegistrationView(result.subjectNo(), result.status().name());
    }
}
