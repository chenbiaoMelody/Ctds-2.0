package com.ctds.subject.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 主体服务公共支撑（WBS-3.1.4 承接 3.1.4 视角评审观察项①：注册与认证两个应用服务的
 * 申请编号格式校验、当前操作人取值、审计落痕三处同型工具方法上收，消除第二使用方重复）。
 * 审计对象域固定 subject（ADR-016 审计口径）。
 */
@Component
public class SubjectOpsSupport {

    /** 申请编号格式（与申请编号生成规则 S + yyyyMMdd + 6 位序号对齐）。 */
    private static final String SUBJECT_NO_PATTERN = "S\\d{14}";

    private final AuditRecorder auditRecorder;

    public SubjectOpsSupport(final AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /** 申请编号格式校验（不合法即 1000C0002 参数错误，不泄露存在性）。 */
    public void requireSubjectNo(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank() || !subjectNo.matches(SUBJECT_NO_PATTERN)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "申请编号格式不正确");
        }
    }

    /** 当前操作人（网关身份缺失时落 anonymous，不阻断——演示链路口径）。 */
    public String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    /** 审计落痕（reason 为空时不带明细字段）。 */
    public void audit(final String operator, final String action, final String subjectNo,
            final AuditOutcome outcome, final String reason) {
        final var detail = reason == null ? null : Map.of("reason", reason);
        auditRecorder.record(AuditEvent.of(operator, action, "subject", subjectNo, outcome, detail));
    }
}
