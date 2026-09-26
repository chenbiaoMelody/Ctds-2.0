package com.ctds.space.domain;

/**
 * 留痕对象类型（space_action_log.target_type 值域）。
 */
public enum TargetType {

    /** 空间本身（space.id）。 */
    SPACE("空间"),
    /** 成员关系（space_member.id）。 */
    MEMBER("成员"),
    /** 准入单（space_admission.id）。 */
    ADMISSION("准入单"),
    /** 策略条目（space_policy.id）。 */
    POLICY("策略条目");

    private final String displayName;

    TargetType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
