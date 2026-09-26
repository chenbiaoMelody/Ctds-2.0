package com.ctds.space.domain;

/**
 * 空间角色（规格 §7 Q3 裁决三档；只读审计角色归 C-9 审计域，本域不新增）。
 * 权限点映射与角色判定归 3.2.4（复用 common 鉴权），本枚举为值域契约。
 */
public enum MemberRole {

    /** 所有者：唯一且不可空缺（行为 5 规则 3 唯一所有者保护，DB 层 uk_active_owner 兜底）。 */
    OWNER("所有者"),
    /** 管理员：由所有者授予，可管理成员与空间配置。 */
    ADMIN("管理员"),
    /** 成员：经准入进入空间的参与方。 */
    MEMBER("成员");

    private final String displayName;

    MemberRole(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
