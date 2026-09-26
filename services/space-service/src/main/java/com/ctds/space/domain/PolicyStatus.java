package com.ctds.space.domain;

/**
 * 策略条目状态（规格行为 7 规则 5 生命周期联动）。
 * 空间解散时该空间全部条目置 ARCHIVED（归档不可变、保留可查）；归档动作归 3.2.3。
 */
public enum PolicyStatus {

    /** 生效。 */
    ACTIVE("生效"),
    /** 归档不可变（空间已解散；保留可查、不得修改）。 */
    ARCHIVED("归档");

    private final String displayName;

    PolicyStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
