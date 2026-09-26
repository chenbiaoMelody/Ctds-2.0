package com.ctds.space.domain;

/**
 * 准入单状态（3.2.2 hifi §1.4 值域；状态机流转细则归 3.2.4 实施包定稿，不得跳段流转）。
 * 仅 APPROVED 表示成员关系已建立（member_id 非空）；其余状态均不产生成员关系。
 */
public enum AdmissionStatus {

    /** 待审批：申请已提交，等待空间管理员/所有者审批。 */
    PENDING_APPROVAL("待审批"),
    /** 待被邀方确认：邀请已发出，等待被邀方确认。 */
    PENDING_CONFIRMATION("待被邀方确认"),
    /** 已通过：审批通过/被邀方确认，成员关系已建立（member_id 回填）。 */
    APPROVED("已通过"),
    /** 已拒绝：审批未通过（理由见 reason 与留痕）。 */
    REJECTED("已拒绝"),
    /** 被邀方谢绝：被邀方拒绝加入邀请。 */
    DECLINED("被邀方谢绝"),
    /** 已撤回：申请方/邀请方主动撤回。 */
    CANCELLED("已撤回");

    private final String displayName;

    AdmissionStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
