package com.ctds.subject.application;

import com.ctds.common.auth.AccessControl;
import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.subject.domain.Subject;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 对象级归属断言（WBS-3.1.3，ADR-016 §2.6 裁决方案①）：一切按申请编号的操作
 * （查询/撤销/上传/确认/核验/档案查询/影像查看/结束认证）先断言
 * "当前身份 = 申请人本人 或 持 subject.review 权限（审核员豁免，3.1.5 复用同一口径）"，
 * 不匹配 → 403 + 拒绝审计留痕（双向用例纪律：本人可操作 + 他人被拒，章程 4）。
 */
@Component
public class OwnershipGuard {

    /** 审核员豁免权限（对象级归属断言的唯一豁免口径；角色映射走既有配置式 RBAC）。 */
    public static final String REVIEW_PERMISSION = "subject.review";

    /** 审计动作前缀（越权拒绝留痕：谁在何时对哪个申请编号做了什么被拒）。 */
    private static final String OWNERSHIP_DENIED_REASON = "ownership_denied";

    private final AccessControl accessControl;
    private final AuditRecorder auditRecorder;

    public OwnershipGuard(final AccessControl accessControl, final AuditRecorder auditRecorder) {
        this.accessControl = accessControl;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 断言当前身份可操作该主体档案：申请人本人或持审核权限，否则 403（复用平台鉴权出站口径）
     * 并落 DENIED 审计（ADR-016 §2.6：拒绝留痕）。
     *
     * @param subject 被操作主体
     * @param action  审计动作标识（如 subject.read / certification.verify）
     */
    public void requireOwnerOrReviewer(final Subject subject, final String action) {
        final String operator = AuthContext.subject();
        if (isOwnerOrReviewer(operator, subject)) {
            return;
        }
        auditRecorder.record(AuditEvent.of(operator, action, "subject", subject.subjectNo(),
                AuditOutcome.DENIED, Map.of("reason", OWNERSHIP_DENIED_REASON)));
        throw new AuthException(ErrorCodes.FORBIDDEN, AuthAdvice.FORBIDDEN_MESSAGE);
    }

    private boolean isOwnerOrReviewer(final String operator, final Subject subject) {
        if (operator == null) {
            return false;
        }
        if (operator.equals(subject.applicant())) {
            return true;
        }
        return accessControl.hasPermission(REVIEW_PERMISSION);
    }
}
