package com.ctds.subject.domain;

import java.time.LocalDateTime;

/**
 * 主体（一行 = 一个主体；申请编号为业务标识，重报复用行不新建——lofi Q3-A 裁决口径，
 * 统一社会信用代码以唯一索引终身占用）。
 *
 * <p>applicant = 申请人身份（WBS-3.1.3，ADR-016 §2.6 裁决方案①）：注册建档时取 AuthContext 当前身份，
 * 对象级归属断言（查询/撤销/认证操作）的数据基础；重报不改变申请人。</p>
 */
public record Subject(
        Long id,
        String subjectNo,
        String subjectName,
        String uscc,
        SubjectType subjectType,
        String regAddress,
        String contactName,
        String contactPhone,
        String adminAccount,
        String applicant,
        SubjectStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
