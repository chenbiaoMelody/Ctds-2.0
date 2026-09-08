package com.ctds.common.auth;

import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.util.Map;

/**
 * 判定内核：RBAC = 当前上下文任一角色在映射表中的权限并集是否含目标权限（模式 A）。
 * 注解与代码内调用共用本类，保证两种写法错误/审计完全一致。
 */
public class DefaultAccessControl implements AccessControl {

    static final String AUDIT_ACTION_RBAC_CHECK = "rbac.check";

    private final RolePermissionMapper mapper;
    /** 审计器可缺省（ctds.audit.enabled=false 时无 Bean），缺省则不联动。 */
    private final AuditRecorder auditRecorder;
    private final boolean auditEnabled;

    public DefaultAccessControl(final RolePermissionMapper mapper, final AuditRecorder auditRecorder,
            final boolean auditEnabled) {
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
        this.auditEnabled = auditEnabled;
    }

    @Override
    public boolean hasPermission(final String permission) {
        if (permission == null || !AuthContext.isAuthenticated()) {
            return false;
        }
        return AuthContext.roles().stream()
                .anyMatch(role -> mapper.permissionsOf(role).contains(permission));
    }

    @Override
    public void require(final String permission) {
        if (!AuthContext.isAuthenticated()) {
            throw new AuthException(ErrorCodes.UNAUTHORIZED, AuthAdvice.UNAUTHORIZED_MESSAGE);
        }
        if (!hasPermission(permission)) {
            recordDenied(permission);
            throw new AuthException(ErrorCodes.FORBIDDEN, AuthAdvice.FORBIDDEN_MESSAGE);
        }
    }

    private void recordDenied(final String permission) {
        if (!auditEnabled || auditRecorder == null) {
            return;
        }
        // 审计铁律：rbac.check DENIED，actor=当前身份，detail 仅 permission（禁含令牌/个人信息）
        auditRecorder.record(AuditEvent.of(AuthContext.subject(), AUDIT_ACTION_RBAC_CHECK, "permission", null,
                AuditOutcome.DENIED, Map.of("permission", permission == null ? "null" : permission)));
    }
}
