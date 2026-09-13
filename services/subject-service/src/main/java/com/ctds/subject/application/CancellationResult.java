package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;

/** 撤销结果（应用层出参；撤销 = 复用记录 + 撤销留痕，状态保持待认证——lofi Q3-A）。 */
public record CancellationResult(String subjectNo, SubjectStatus status, boolean cancelled) {
}
