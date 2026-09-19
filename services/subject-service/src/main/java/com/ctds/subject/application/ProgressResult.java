package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;

/**
 * 入驻进度查询结果（CHG-C-1.1-V1.2 规格行为 8 第 2 条最小必要五字段：
 * 不含联系人/电话/证照/核验信息；rejectReason = 最近一条"转已驳回"流转备注，非驳回态为 null）。
 */
public record ProgressResult(String subjectNo, String subjectName, SubjectType subjectType,
        SubjectStatus status, String rejectReason) {
}
