package com.ctds.subject.domain;

import java.time.LocalDateTime;

/**
 * 主体（一行 = 一个主体；申请编号为业务标识，重报复用行不新建——lofi Q3-A 裁决口径，
 * 统一社会信用代码以唯一索引终身占用）。
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
        SubjectStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
