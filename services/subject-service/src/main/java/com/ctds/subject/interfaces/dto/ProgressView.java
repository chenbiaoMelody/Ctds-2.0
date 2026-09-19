package com.ctds.subject.interfaces.dto;

import com.ctds.subject.application.ProgressResult;

/**
 * 入驻进度查询视图（CHG-C-1.1-V1.2 规格行为 8 第 2 条：仅最小必要五字段，
 * 无联系人/电话/证照/核验信息——双凭证查询对外信息面最小化）。
 */
public record ProgressView(String subjectNo, String subjectName, String subjectType,
        String status, String rejectReason) {

    public static ProgressView from(final ProgressResult result) {
        return new ProgressView(result.subjectNo(), result.subjectName(), result.subjectType().name(),
                result.status().name(), result.rejectReason());
    }
}
