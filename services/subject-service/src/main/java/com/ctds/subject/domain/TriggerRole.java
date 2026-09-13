package com.ctds.subject.domain;

/** 状态流转触发方（规格 C-1.1 行为 4 第 2 条四要素之一）。 */
public enum TriggerRole {

    /** 申请人。 */
    APPLICANT("申请人"),
    /** 系统（认证通过自动流转等，3.1.3）。 */
    SYSTEM("系统"),
    /** 审核员（3.1.5）。 */
    REVIEWER("审核员");

    private final String displayName;

    TriggerRole(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
