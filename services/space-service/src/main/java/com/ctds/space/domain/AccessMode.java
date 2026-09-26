package com.ctds.space.domain;

/**
 * 参与方范围（空间对外接纳成员的口径；规格 §7 Q2 裁决三档）。
 * 准入形态由本档位决定：公开/审批制 = 申请+审批，邀请制 = 邀请+被邀方确认（判定逻辑归 3.2.4）。
 */
public enum AccessMode {

    /** 公开：可检索 + 申请 + 审批。 */
    OPEN("公开"),
    /** 邀请制：邀请 + 被邀方确认。 */
    INVITE("邀请制"),
    /** 审批制：不公开 + 申请 + 审批（与公开的差异在可见性，见 Visibility）。 */
    APPROVAL("审批制");

    private final String displayName;

    AccessMode(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
