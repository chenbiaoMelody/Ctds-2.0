package com.ctds.space.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.auth.ConfigRolePermissionMapper;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.SpacePermissions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 统一权限面映射矩阵纯单测（WBS-3.2.4 hifi §5 / T17；规格行为 4 规则 4 权限点定稿）：
 * 空间内角色折算（OWNER/ADMIN → 两点、MEMBER → member）+ 角色头折算（经 common
 * RolePermissionMapper 读 ctds.auth.permissions 配置——space yml 定稿 platform.operator → 两点）。
 * 纯函数无 AuthContext 静态依赖（AuthContext 部分=集成测试 MockMvc 身份头覆盖）。
 */
class SpaceAccessGuardPermissionMatrixTest {

    private final ConfigRolePermissionMapper mapper =
            new ConfigRolePermissionMapper(Map.of("platform.operator", "space.admin,space.member"));

    // ==== 空间内角色 → 权限点（hifi §5 矩阵）====

    @Test
    void ownerGrantsBothPermissionPoints() {
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.OWNER, SpacePermissions.SPACE_ADMIN)).isTrue();
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.OWNER, SpacePermissions.SPACE_MEMBER)).isTrue();
    }

    @Test
    void adminGrantsBothPermissionPoints() {
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.ADMIN, SpacePermissions.SPACE_ADMIN)).isTrue();
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.ADMIN, SpacePermissions.SPACE_MEMBER)).isTrue();
    }

    @Test
    void memberGrantsMemberPointOnly() {
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.MEMBER, SpacePermissions.SPACE_MEMBER)).isTrue();
        assertThat(SpaceAccessGuard.spaceRoleGrants(MemberRole.MEMBER, SpacePermissions.SPACE_ADMIN)).isFalse();
    }

    // ==== 角色头 → 权限点（yml 定稿映射，替换 3.2.3 零消费的 space.manage）====

    @Test
    void platformOperatorGrantsBothPermissionPointsViaConfiguredMapping() {
        assertThat(SpaceAccessGuard.roleHeaderGrants(mapper, Set.of("platform.operator"),
                SpacePermissions.SPACE_ADMIN)).isTrue();
        assertThat(SpaceAccessGuard.roleHeaderGrants(mapper, Set.of("platform.operator"),
                SpacePermissions.SPACE_MEMBER)).isTrue();
    }

    @Test
    void unknownRoleGrantsNothing() {
        assertThat(SpaceAccessGuard.roleHeaderGrants(mapper, Set.of("user", "space-internal"),
                SpacePermissions.SPACE_ADMIN)).isFalse();
        assertThat(SpaceAccessGuard.roleHeaderGrants(mapper, Set.of(),
                SpacePermissions.SPACE_MEMBER)).isFalse();
    }

    @Test
    void permissionPointConstantsAreFinalizedNames() {
        // 权限点命名定稿（规格行为 4 规则 4"形如 space.admin/space.member"）——重命名 = 设计变更
        assertThat(SpacePermissions.SPACE_ADMIN).isEqualTo("space.admin");
        assertThat(SpacePermissions.SPACE_MEMBER).isEqualTo("space.member");
    }
}
