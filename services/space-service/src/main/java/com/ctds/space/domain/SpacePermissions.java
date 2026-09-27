package com.ctds.space.domain;

/**
 * 空间域权限点定稿（WBS-3.2.4 hifi §5，规格行为 4 规则 4"权限点命名随 3.2.4 定稿"）：
 * space.admin = 管理动作（邀请/审批/准入单管理/移除/角色变更/冻结等生命周期管理动作）；
 * space.member = 空间内读取与使用/退出/成员列表。
 *
 * <p>映射矩阵单一来源 = application.SpaceAccessGuard（空间内角色折算 + 角色头经 common
 * RolePermissionMapper 读 ctds.auth.permissions，space yml 定稿 platform.operator → 两点）。
 * owner 专属动作（解散/所有权转移）走属主判定（space.owner_subject_no 列口径），与权限点正交——
 * owner 是"该空间的归属事实"，不是可授予的能力（全平台唯一、不可授予他人）。</p>
 */
public final class SpacePermissions {

    /** 管理动作权限点。 */
    public static final String SPACE_ADMIN = "space.admin";
    /** 成员动作权限点。 */
    public static final String SPACE_MEMBER = "space.member";

    private SpacePermissions() {
    }
}
