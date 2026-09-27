package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.application.SpaceAdmissionService;
import com.ctds.space.application.SpaceAdmissionService.AdmissionOutcome;
import com.ctds.space.application.SpaceMemberService;
import com.ctds.space.domain.AdmissionStatus;
import com.ctds.space.domain.SpaceAdmission;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.interfaces.dto.AdmissionOperationView;
import com.ctds.space.interfaces.dto.AdmissionView;
import com.ctds.space.interfaces.dto.MemberView;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.ApprovalRequest;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.ConfirmationRequest;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.InvitationRequest;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.OwnershipTransferRequest;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.OwnershipTransferView;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.RemovalRequest;
import com.ctds.space.interfaces.dto.SpaceMembershipRequests.RoleAssignmentRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间成员与权限端点（WBS-3.2.4 hifi §1 端点 1~11，ADR-005 资源命名 /api/v1/data-spaces）：
 * 准入面 5（申请/邀请/确认/审批/空间准入单列表）+ 个人面 1（我的准入单）+ 成员面 5
 * （成员列表/角色变更/移除/退出/所有权转移）。操作者身份取 AuthContext（X-Ctds-Subject 演示期
 * 身份头口径），不收请求体传入；权限判定为统一权限面动态判定（SpaceAccessGuard），不经注解静态门。
 */
@RestController
@RequestMapping("/api/v1/data-spaces")
public class SpaceMembershipController {

    private final SpaceAdmissionService admissionService;
    private final SpaceMemberService memberService;

    public SpaceMembershipController(final SpaceAdmissionService admissionService,
            final SpaceMemberService memberService) {
        this.admissionService = admissionService;
        this.memberService = memberService;
    }

    /** 端点 1 提交加入申请（公开/审批制；申请人 = 登录主体；重复提交幂等返回既有）。 */
    @PostMapping(path = "/{id}/admissions/applications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<AdmissionOperationView> apply(@PathVariable final long id) {
        return ApiResult.ok(operationView(admissionService.apply(id)));
    }

    /** 端点 2 发出邀请（邀请制；owner/admin；被邀主体须 ADMITTED，提交时即拒）。 */
    @PostMapping(path = "/{id}/admissions/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<AdmissionOperationView> invite(@PathVariable final long id,
            @RequestBody final InvitationRequest request) {
        return ApiResult.ok(operationView(admissionService.invite(id, request.subjectNo(), request.reason())));
    }

    /** 端点 3 被邀方确认/谢绝（仅被邀方本人；确认 = 成员生效事务）。 */
    @PostMapping(path = "/{spaceId}/admissions/{admissionId}/confirmation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<AdmissionView> confirm(@PathVariable final long spaceId,
            @PathVariable final long admissionId, @RequestBody final ConfirmationRequest request) {
        final boolean accept = request.decision() == ConfirmationRequest.Decision.CONFIRM;
        return ApiResult.ok(AdmissionView.from(admissionService.confirm(spaceId, admissionId, accept,
                request.reason())));
    }

    /** 端点 4 审批通过/拒绝（owner/admin；拒绝理由必填）。 */
    @PostMapping(path = "/{spaceId}/admissions/{admissionId}/approval",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<AdmissionView> approve(@PathVariable final long spaceId,
            @PathVariable final long admissionId, @RequestBody final ApprovalRequest request) {
        final boolean approve = request.decision() == ApprovalRequest.Decision.APPROVE;
        return ApiResult.ok(AdmissionView.from(admissionService.approve(spaceId, admissionId, approve,
                request.reason())));
    }

    /** 端点 5 空间准入单列表（owner/admin 待办发现面；status 筛选可选）。 */
    @GetMapping(path = "/{id}/admissions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<AdmissionView>> listAdmissions(@PathVariable final long id,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final AdmissionStatus status) {
        return ApiResult.ok(admissionViewPage(admissionService.listBySpace(id, status,
                PageQuery.of(pageNum, pageSize, null))));
    }

    /** 端点 6 我的准入单（个人视角：发出的申请 + 收到的邀请——剧本"我的邀请等价入口"）。 */
    @GetMapping(path = "/admissions/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<AdmissionView>> myAdmissions(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(admissionViewPage(admissionService.listMine(PageQuery.of(pageNum, pageSize, null))));
    }

    /** 端点 7 成员列表（成员可见 = space.member 权限点；非成员 1006C0007 + ACCESS_DENIED 留痕）。 */
    @GetMapping(path = "/{id}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<MemberView>> listMembers(@PathVariable final long id,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<SpaceMember> result = memberService.listMembers(id,
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(memberViewPage(result));
    }

    /** 端点 8 角色授予/收回（owner/admin；目标角色 ADMIN=授予 / MEMBER=收回；OWNER 拒）。 */
    @PostMapping(path = "/{id}/members/{memberId}/role-assignment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<MemberView> assignRole(@PathVariable final long id, @PathVariable final long memberId,
            @RequestBody final RoleAssignmentRequest request) {
        return ApiResult.ok(MemberView.from(memberService.assignRole(id, memberId, request.role())));
    }

    /** 端点 9 移除成员（owner/admin；理由必填；不得移除所有者）。 */
    @PostMapping(path = "/{id}/members/{memberId}/removal", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<MemberView> remove(@PathVariable final long id, @PathVariable final long memberId,
            @RequestBody final RemovalRequest request) {
        return ApiResult.ok(MemberView.from(memberService.remove(id, memberId, request.reason())));
    }

    /** 端点 10 主动退出（成员本人；所有者受唯一所有者保护拒绝）。 */
    @PostMapping(path = "/{id}/leaving", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<MemberView> leave(@PathVariable final long id) {
        return ApiResult.ok(MemberView.from(memberService.leave(id)));
    }

    /** 端点 11 所有权转移（仅 owner 本人发起；单事务四写双处同步）。 */
    @PostMapping(path = "/{id}/ownership-transfer", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<OwnershipTransferView> transferOwnership(@PathVariable final long id,
            @RequestBody final OwnershipTransferRequest request) {
        final SpaceMemberService.OwnershipTransferOutcome outcome =
                memberService.transferOwnership(id, request.targetMemberId());
        return ApiResult.ok(new OwnershipTransferView(outcome.spaceOwnerSubjectNo(),
                MemberView.from(outcome.formerOwner()), MemberView.from(outcome.newOwner()),
                outcome.transferredAt()));
    }

    private static AdmissionOperationView operationView(final AdmissionOutcome outcome) {
        return new AdmissionOperationView(outcome.alreadyMember(),
                outcome.admission() == null ? null : AdmissionView.from(outcome.admission()),
                outcome.member() == null ? null : MemberView.from(outcome.member()));
    }

    private static PageResult<AdmissionView> admissionViewPage(final PageResult<SpaceAdmission> result) {
        return new PageResult<>(result.list().stream().map(AdmissionView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
    }

    private static PageResult<MemberView> memberViewPage(final PageResult<SpaceMember> result) {
        return new PageResult<>(result.list().stream().map(MemberView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
    }
}
