package com.ctds.space.domain;

/**
 * 空间可见性（规格 §7 Q6 裁决：只管"空间本身可否被检索/申请"，不等于资源可访问性——行为 6 规则 3）。
 */
public enum Visibility {

    /** 公开：空间本身可被检索/申请。 */
    PUBLIC("公开"),
    /** 不公开：仅成员可见（审批制空间的取值）。 */
    PRIVATE("不公开");

    private final String displayName;

    Visibility(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
