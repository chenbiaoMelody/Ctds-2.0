package com.ctds.subject.interfaces.dto;

import com.ctds.subject.application.SubjectDetail;
import com.ctds.subject.domain.Subject;
import java.util.List;

/**
 * 主体进度视图（hifi 接口契约：注册信息 + 当前状态 + 流转记录；联系电话脱敏展示由 interfaces 层执行——
 * 存储明文、展示脱敏，L3/L4 展示管控口径沿分级规范 §5）。
 */
public record SubjectView(String subjectNo, String subjectName, String uscc, String subjectType,
        String regAddress, String contactName, String contactPhone, String status, List<StatusLogView> statusLogs) {

    public static SubjectView from(final SubjectDetail detail, final String maskedPhone) {
        final Subject subject = detail.subject();
        return new SubjectView(subject.subjectNo(), subject.subjectName(), subject.uscc(),
                subject.subjectType().name(), subject.regAddress(), subject.contactName(), maskedPhone,
                subject.status().name(),
                detail.transitions().stream().map(StatusLogView::from).toList());
    }
}
