package com.ctds.space.domain;

/**
 * 准入形态（规格行为 3 规则 2：由空间 access_mode 决定）。
 * 邀请未经被邀方确认不产生成员关系——准入单与成员关系两表分工（3.2.2 hifi §1.4）。
 */
public enum AdmissionType {

    /** 申请：公开/审批制空间，主体自助提交，待空间管理员/所有者审批。 */
    APPLICATION("申请"),
    /** 邀请：所有者/管理员发出，待被邀方确认。 */
    INVITATION("邀请");

    private final String displayName;

    AdmissionType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
