package com.ctds.space.domain;

/**
 * 成员关系状态（规格行为 3/4/5）。终态行保留供追溯（退出/移除不删行），再次准入生成新行——
 * 同一空间同一主体至多一条生效关系由 DB 层 uk_active_member 兜底。
 */
public enum MemberStatus {

    /** 生效中：可访问空间内资源（访问判定归 3.2.4）。 */
    ACTIVE("生效中"),
    /** 已退出：成员主动退出（关系终止，行保留）。 */
    LEFT("已退出"),
    /** 已移除：被所有者/管理员移除（关系终止，行保留，理由见留痕）。 */
    REMOVED("已移除");

    private final String displayName;

    MemberStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
