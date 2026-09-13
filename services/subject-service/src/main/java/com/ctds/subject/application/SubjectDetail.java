package com.ctds.subject.application;

import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.StatusTransition;
import java.util.List;

/** 主体认证档案视图（应用层出参：注册信息 + 当前状态 + 全部流转留痕）。 */
public record SubjectDetail(Subject subject, List<StatusTransition> transitions) {
}
