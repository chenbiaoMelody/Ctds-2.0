package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;

/**
 * 结束认证结果（lofi Q1-A 裁决口径：待认证 → 认证失败）。
 */
public record CertificationActionResult(String subjectNo, SubjectStatus status) {
}
