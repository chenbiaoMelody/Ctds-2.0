package com.ctds.space.application;

import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthException;
import com.ctds.common.auth.RolePermissionMapper;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.SpacePermissions;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceMember;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空间动作统一权限面（WBS-3.2.4 hifi §5，3.2.3 移交①收敛兑现；规格行为 4 规则 3/4 服务端强制）：
 * 动作 → 所需权限点（space.admin / space.member，domain.SpacePermissions 定稿）→ 权限点来源折算——
 * ① 空间内角色（查 space_member 活跃行：OWNER/ADMIN → 两点，MEMBER → member）；
 * ② 平台角色头（经 common RolePermissionMapper 读 ctds.auth.permissions，space yml 定稿
 * platform.operator → space.admin,space.member——替换 3.2.3 零消费的 space.manage 登记）。
 * owner 专属动作（解散/所有权转移）走属主判定（space.owner_subject_no 列口径，与权限点正交）。
 * 逐动作判定失败由调用方落 DENIED 留痕后抛 1006C0007（判定在应用服务，非注解静态门——
 * 判定输入含成员表数据）。既有方法签名与判定结果语义不变（3.2.3 T10 双轨测试不回归）。
 */
@Component
public class SpaceAccessGuard {

    /** 默认平台运营方角色档（角色头传入；可用 ctds.space.operator-role 调整）。 */
    private static final String DEFAULT_OPERATOR_ROLE = "platform.operator";

    private final String operatorRole;
    private final RolePermissionMapper rolePermissionMapper;

    public SpaceAccessGuard(@Value("${ctds.space.operator-role:" + DEFAULT_OPERATOR_ROLE + "}")
            final String operatorRole, final RolePermissionMapper rolePermissionMapper) {
        this.operatorRole = operatorRole;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    /** 当前请求方是否平台运营方（角色头档；属主/治理通道判定用，语义沿 3.2.3）。 */
    public boolean isPlatformOperator() {
        return AuthContext.roles().contains(operatorRole);
    }

    /** 当前请求方是否空间所有者（space.owner_subject_no 列口径，与成员表活跃 OWNER 行一致性由 uk_active_owner 兜底）。 */
    public boolean isOwner(final Space space) {
        return space.ownerSubjectNo().equals(AuthContext.subject());
    }

    /**
     * 管理类动作判定（space.admin 权限点：邀请/审批/移除/角色变更/启用/冻结/恢复/配置变更）。
     * 判定失败由调用方落 DENIED 留痕后抛 1006C0007。
     */
    public boolean canManage(final Space space, final List<SpaceMember> activeMembers) {
        return hasPermission(space, activeMembers, SpacePermissions.SPACE_ADMIN);
    }

    /**
     * 成员动作判定（space.member 权限点：空间内读取与使用/退出/成员列表）。
     * 判定失败由调用方落 DENIED/ACCESS_DENIED 留痕后抛对应业务码。
     */
    public boolean canActAsMember(final Space space, final List<SpaceMember> activeMembers) {
        return hasPermission(space, activeMembers, SpacePermissions.SPACE_MEMBER);
    }

    /** 解散判定：仅所有者或 platform.operator（admin 不可解散——行为 2 规则 4；语义沿 3.2.3 不变）。 */
    public boolean canDissolve(final Space space) {
        return isPlatformOperator() || isOwner(space);
    }

    /**
     * 统一权限点判定（映射矩阵单一来源，hifi §5）：任一来源折算命中即放行。
     *
     * @param space         目标空间（属主判定入参保留——空间内角色折算只依赖成员行，属主动作另行走 isOwner）
     * @param activeMembers 空间活跃成员行（判定数据源）
     * @param permissionPoint 所需权限点（domain.SpacePermissions 定稿值域）
     */
    public boolean hasPermission(final Space space, final List<SpaceMember> activeMembers,
            final String permissionPoint) {
        final String subject = AuthContext.subject();
        if (subject != null && activeMembers.stream().anyMatch(member ->
                subject.equals(member.subjectNo()) && spaceRoleGrants(member.role(), permissionPoint))) {
            return true;
        }
        return roleHeaderGrants(rolePermissionMapper, AuthContext.roles(), permissionPoint);
    }

    /** 当前登录主体；未认证 = 401（AuthAdvice 精确映射，沿平台鉴权口径）。 */
    public String requireSubject() {
        final String subject = AuthContext.subject();
        if (subject == null) {
            throw new AuthException(ErrorCodes.UNAUTHORIZED, AuthAdvice.UNAUTHORIZED_MESSAGE);
        }
        return subject;
    }

    /**
     * 空间内角色 → 权限点映射（映射矩阵纯函数，单测直接覆盖——包可见避免 AuthContext 静态依赖）：
     * OWNER/ADMIN → space.admin + space.member；MEMBER → space.member（hifi §5 矩阵）。
     */
    static boolean spaceRoleGrants(final MemberRole role, final String permissionPoint) {
        return switch (role) {
            case OWNER, ADMIN -> SpacePermissions.SPACE_ADMIN.equals(permissionPoint)
                    || SpacePermissions.SPACE_MEMBER.equals(permissionPoint);
            case MEMBER -> SpacePermissions.SPACE_MEMBER.equals(permissionPoint);
        };
    }

    /** 角色头 → 权限点折算（经 common RolePermissionMapper 读配置映射；任一角色命中即放行）。 */
    static boolean roleHeaderGrants(final RolePermissionMapper mapper, final Set<String> roles,
            final String permissionPoint) {
        return roles.stream().anyMatch(role -> mapper.permissionsOf(role).contains(permissionPoint));
    }
}
