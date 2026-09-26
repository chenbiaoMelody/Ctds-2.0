package com.ctds.space.domain;

/**
 * 策略条目作用域（规格行为 7 规则 1：平台级默认 + 空间级默认继承）。
 * 覆盖行经 platform_entry_id 显式指向被覆盖的平台级条目。
 */
public enum PolicyScope {

    /** 平台级：默认来源（space_id 为 NULL）。 */
    PLATFORM("平台级"),
    /** 空间级：空间覆盖（space_id 必填，platform_entry_id 指向被覆盖条目）。 */
    SPACE("空间级");

    private final String displayName;

    PolicyScope(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
