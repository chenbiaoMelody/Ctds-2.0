package com.ctds.space.application;

import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceMember;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空间动作双轨权限判定（WBS-3.2.3 hifi §5 Q4-A）：
 * ① 平台角色 = 角色头档（platform.operator，配置可调；演示期角色映射随 space yml 登记）；
 * ② 空间内角色 = 查 space_member 活跃行（OWNER/ADMIN），解散仅 OWNER（行为 2 规则 4"仅所有者动作"，
 * admin 不可解散）。逐动作判定失败 → 1006C0007 + DENIED 留痕（判定在应用服务，非注解静态门——
 * 判定输入含成员表数据；权限点命名随 3.2.4 收敛，规格行为 4 规则 4）。
 */
@Component
public class SpaceAccessGuard {

    /** 默认平台运营方角色档（角色头传入；可用 ctds.space.operator-role 调整）。 */
    private static final String DEFAULT_OPERATOR_ROLE = "platform.operator";

    private final String operatorRole;

    public SpaceAccessGuard(@Value("${ctds.space.operator-role:" + DEFAULT_OPERATOR_ROLE + "}")
            final String operatorRole) {
        this.operatorRole = operatorRole;
    }

    /** 当前请求方是否平台运营方（角色头档）。 */
    public boolean isPlatformOperator() {
        return AuthContext.roles().contains(operatorRole);
    }

    /** 当前请求方是否空间所有者（space.owner_subject_no 口径，与成员表活跃 OWNER 行一致性由 uk_active_owner 兜底）。 */
    public boolean isOwner(final Space space) {
        return space.ownerSubjectNo().equals(AuthContext.subject());
    }

    /**
     * 管理类动作判定（启用/冻结/恢复/配置变更）：owner/admin（成员表活跃行）或 platform.operator
     * （行为 2 规则 3/5）。判定失败由调用方落 DENIED 留痕后抛 1006C0007。
     */
    public boolean canManage(final Space space, final List<SpaceMember> activeMembers) {
        if (isPlatformOperator() || isOwner(space)) {
            return true;
        }
        final String subject = AuthContext.subject();
        return subject != null && activeMembers.stream().anyMatch(member ->
                subject.equals(member.subjectNo()) && isActiveRole(member));
    }

    /** 解散判定：仅所有者或 platform.operator（admin 不可解散——行为 2 规则 4）。 */
    public boolean canDissolve(final Space space) {
        return isPlatformOperator() || isOwner(space);
    }

    /** 当前登录主体；未认证 = 401（AuthAdvice 精确映射，沿平台鉴权口径）。 */
    public String requireSubject() {
        final String subject = AuthContext.subject();
        if (subject == null) {
            throw new AuthException(ErrorCodes.UNAUTHORIZED, AuthAdvice.UNAUTHORIZED_MESSAGE);
        }
        return subject;
    }

    private static boolean isActiveRole(final SpaceMember member) {
        return member.status() == MemberStatus.ACTIVE
                && (member.role() == MemberRole.OWNER || member.role() == MemberRole.ADMIN);
    }
}
