package com.ctds.subject.domain;

/**
 * 主体状态机（规格 C-1.1 行为 4 的 V1.0 最小集，实施包不得增删——hifi B7）。
 * 流转触发端：注册→待认证（本包 3.1.2）；待认证→待审核/认证失败（3.1.3）；待审核→已入驻/已驳回（3.1.5）。
 */
public enum SubjectStatus {

    /** 待认证：注册成功初始态。 */
    PENDING_CERT("待认证"),
    /** 待审核：认证材料齐备（OCR 确认 + 核验通过，或政务 CA 验证通过）。 */
    PENDING_REVIEW("待审核"),
    /** 已入驻：审核通过稳定态。 */
    ADMITTED("已入驻"),
    /** 认证失败：核验不通过且申请人主动结束或超限。 */
    CERT_FAILED("认证失败"),
    /** 已驳回：审核驳回终态（附理由），可修改后重新申请。 */
    REJECTED("已驳回");

    private final String displayName;

    SubjectStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
