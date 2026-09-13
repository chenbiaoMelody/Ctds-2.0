package com.ctds.subject.application;

/**
 * 审核操作结果（WBS-3.1.5 hifi 接口契约；status = ADMITTED / REJECTED）。
 */
public record ReviewActionResult(String subjectNo, String status) {
}
