package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.MemberRole;
import java.time.LocalDateTime;

/**
 * 成员域请求载荷集（WBS-3.2.4 hifi §1 端点 2/3/4/8/9/11；decision 用嵌套枚举封闭值域，
 * 非法值 = 请求体格式不合法走通用 400 通道；reason 长度门槛在应用服务校验，沿仓内先例不用 Bean Validation）。
 */
public final class SpaceMembershipRequests {

    /** 端点 2 发出邀请（被邀主体编号 + 可选理由）。 */
    public record InvitationRequest(String subjectNo, String reason) {
    }

    /** 端点 3 被邀方确认/谢绝（decision 封闭值域：CONFIRM=接受 / DECLINE=谢绝）。 */
    public record ConfirmationRequest(Decision decision, String reason) {

        public enum Decision {
            CONFIRM, DECLINE
        }
    }

    /** 端点 4 审批（decision 封闭值域：APPROVE=通过 / REJECT=拒绝，拒绝理由应用服务强制必填）。 */
    public record ApprovalRequest(Decision decision, String reason) {

        public enum Decision {
            APPROVE, REJECT
        }
    }

    /** 端点 8 角色变更（目标角色仅 ADMIN=授予 / MEMBER=收回；OWNER 非可授予能力，应用服务拒）。 */
    public record RoleAssignmentRequest(MemberRole role) {
    }

    /** 端点 9 移除成员（理由必填，应用服务校验）。 */
    public record RemovalRequest(String reason) {
    }

    /** 端点 11 所有权转移（目标成员行 id，Q5-A 行级定位）。 */
    public record OwnershipTransferRequest(Long targetMemberId) {
    }

    /** 端点 11 出参：双行回显（原 owner 降级行 + 新 owner 行）。 */
    public record OwnershipTransferView(String spaceOwnerSubjectNo, MemberView formerOwner,
            MemberView newOwner, LocalDateTime transferredAt) {
    }

    private SpaceMembershipRequests() {
    }
}
