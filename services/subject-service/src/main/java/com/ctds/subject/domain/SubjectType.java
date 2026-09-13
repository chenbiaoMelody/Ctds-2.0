package com.ctds.subject.domain;

/** 主体类型（规格 C-1.1 行为 1：企业/机构/政府部门；政务 CA 通道差异归行为 6 / 3.1.4）。 */
public enum SubjectType {

    /** 企业。 */
    ENTERPRISE("企业"),
    /** 机构。 */
    INSTITUTION("机构"),
    /** 政府部门。 */
    GOV("政府部门");

    private final String displayName;

    SubjectType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
