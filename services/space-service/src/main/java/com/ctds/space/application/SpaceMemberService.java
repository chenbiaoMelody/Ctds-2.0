package com.ctds.space.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空间成员关系服务（WBS-3.2.4 hifi §4，规格行为 4 角色边界 + 行为 5 退出移除）：
 * 成员列表（space.member 读面）、角色授予/收回（owner 行不参与，自我提权门槛）、
 * 移除（reason 必填，冻结期允许——Q6-A 冻结只封"进"与"授权"）、主动退出（owner 拒）、
 * 所有权转移（仅 owner 本人发起，单事务四写双处同步——移交②）。
 * 唯一所有者保护 = 应用层显式门槛（1006C0009）：uk_active_owner 只兜"两个 OWNER"并存，
 * 拦不住"OWNER 被降级"，须显式前置判断（3.2.3 终态自环同款教训）。
 */
@Service
public class SpaceMemberService {

    /** 移除理由长度上限（space_action_log.reason 列宽，3.2.2 口径）。 */
    private static final int REASON_MAX_LENGTH = 256;

    private final SpaceRepository repository;
    private final SpaceAccessGuard guard;
    private final Clock clock;

    public SpaceMemberService(final SpaceRepository repository, final SpaceAccessGuard guard, final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.clock = clock;
    }

    // ==== 端点 7：成员列表 ====

    /**
     * 成员列表（活跃成员；成员可见 = space.member 权限点，运营方经角色头映射覆盖）。
     * 非成员访问 → 1006C0007 + ACCESS_DENIED 留痕（行为 4 规则 3 + 行为 6 规则 1）。
     */
    public PageResult<SpaceMember> listMembers(final long spaceId, final PageQuery page) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.canActAsMember(space, repository.findActiveMembers(spaceId))) {
            denyRead(space, subject);
        }
        return repository.searchActiveMembers(spaceId, page);
    }

    // ==== 端点 8：角色授予/收回 ====

    /**
     * 角色变更（目标角色 ADMIN = 授予 / MEMBER = 收回；行为 4 规则 2/5）：
     * 门槛链 = space.admin 权限 → 目标角色不得为 OWNER（所有权变更只走转移端点）→ 空间 ACTIVE
     * （行为 5 规则 4 冻结期不得授予角色）→ 目标行定位（非 OWNER 行）→ 自我提权门槛
     * （操作者 = 目标行本人 → 1006C0007）。同角色重复设定 = 幂等无操作（无留痕，登记口径）。
     */
    public SpaceMember assignRole(final long spaceId, final long memberId, final MemberRole targetRole) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        final List<SpaceMember> activeMembers = repository.findActiveMembers(spaceId);
        if (!guard.canManage(space, activeMembers)) {
            deny(space, subject, "ROLE_GRANT", memberId, null);
        }
        if (targetRole == MemberRole.OWNER) {
            // 不得把他人提升为 owner（行为 4 规则 5）——所有权变更只归所有者本人经转移端点发起
            deny(space, subject, "ROLE_GRANT", memberId, "目标角色不可为所有者（所有权变更走转移端点）");
        }
        if (space.status() != SpaceStatus.ACTIVE) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        final SpaceMember target = locateOperableMember(memberId, spaceId);
        if (target.role() == MemberRole.OWNER) {
            // 唯一所有者保护（行为 5 规则 2/3）：owner 行不可角色变更——拒绝留痕与退出路径同口径
            denyOwnerProtected(space, subject, "ROLE_GRANT", target.id());
        }
        if (subject.equals(target.subjectNo())) {
            // 不得自我提权（行为 4 规则 5；member 给自己授 admin / admin 改自己角色均拒）
            deny(space, subject, "ROLE_GRANT", target.id(), null);
        }
        if (target.role() == targetRole) {
            return target;
        }
        final String action = targetRole == MemberRole.ADMIN ? "ROLE_GRANT" : "ROLE_REVOKE";
        repository.changeRole(memberId, spaceId, target.role(), targetRole,
                memberLog(spaceId, memberId, action, subject, target.role().name(), targetRole.name(),
                        ActionResult.SUCCESS, null));
        return repository.findMemberById(memberId)
                .orElseThrow(() -> memberRelationMissing());
    }

    // ==== 端点 9：移除成员 ====

    /**
     * 移除（owner/admin；reason 必填；只能移除 member/admin，不得移除所有者——行为 5 规则 2）。
     * 冻结/解散不阻断（终止成员关系不受阻，行为 5 规则 4 + Q6-A）。
     */
    public SpaceMember remove(final long spaceId, final long memberId, final String reason) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.canManage(space, repository.findActiveMembers(spaceId))) {
            deny(space, subject, "REMOVE", memberId, null);
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "移除成员须填写理由");
        }
        checkReason(reason);
        final SpaceMember target = locateOperableMember(memberId, spaceId);
        if (target.role() == MemberRole.OWNER) {
            // 唯一所有者保护（行为 5 规则 2/3）：owner 行不可移除——拒绝留痕与退出路径同口径
            denyOwnerProtected(space, subject, "REMOVE", target.id());
        }
        repository.terminateMembership(memberId, spaceId, target.role(), MemberStatus.REMOVED,
                memberLog(spaceId, memberId, "REMOVE", subject, target.role().name(),
                        MemberStatus.REMOVED.name(), ActionResult.SUCCESS, reason));
        return repository.findMemberById(memberId)
                .orElseThrow(() -> memberRelationMissing());
    }

    // ==== 端点 10：主动退出 ====

    /**
     * 退出（成员本人；所有者不得退出——须先转让所有权或解散空间，行为 5 规则 1；
     * 冻结/解散状态不阻断——行为 5 规则 4"终止成员关系不受阻"）。
     */
    public SpaceMember leave(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        final List<SpaceMember> activeMembers = repository.findActiveMembers(spaceId);
        final SpaceMember self = activeMembers.stream()
                .filter(member -> subject.equals(member.subjectNo()))
                .findFirst()
                .orElseThrow(() -> memberRelationMissing());
        if (self.role() == MemberRole.OWNER) {
            // 唯一所有者保护（行为 5 规则 1/3）：owner 退出须先转移或解散——显式前置门槛 + 1006C0009
            denyOwnerProtected(space, subject, "LEAVE", self.id());
        }
        repository.terminateMembership(self.id(), spaceId, self.role(), MemberStatus.LEFT,
                memberLog(spaceId, self.id(), "LEAVE", subject, self.role().name(),
                        MemberStatus.LEFT.name(), ActionResult.SUCCESS, null));
        return repository.findMemberById(self.id())
                .orElseThrow(() -> memberRelationMissing());
    }

    // ==== 端点 11：所有权转移 ====

    /**
     * 所有权转移（仅 owner 本人发起——规格行为 4 规则 5"只归所有者本人发起"，platform.operator
     * 亦不可代发；目标 = 本空间活跃成员行且非本人）。仓储单事务四写（移交②双处同步：
     * 先降原 owner ADMIN → 再升目标 OWNER → space.owner_subject_no 列乐观门槛同步 → 留痕两行）。
     */
    public OwnershipTransferOutcome transferOwnership(final long spaceId, final long targetMemberId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.isOwner(space)) {
            // 尝试把目标升 OWNER 被拒：动作本质 = ROLE_GRANT(→OWNER)，复用值域动作码 + reason 说明
            deny(space, subject, "ROLE_GRANT", targetMemberId, "仅空间所有者本人可发起所有权转移");
        }
        final SpaceMember target = repository.findMemberById(targetMemberId)
                .filter(member -> member.spaceId() == spaceId)
                .orElseThrow(() -> memberRelationMissing());
        if (target.status() != MemberStatus.ACTIVE || target.role() == MemberRole.OWNER) {
            // 目标不可用（不存在/不活跃/OWNER 行=转移给自己）统一 1006C0008 文案——防成员存在性探测（hifi §9）
            throw memberRelationMissing();
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final SpaceActionLog grantLog = memberLog(spaceId, targetMemberId, "ROLE_GRANT", subject,
                target.role().name(), MemberRole.OWNER.name(), ActionResult.SUCCESS,
                "所有权转移（新所有者）");
        final SpaceActionLog revokeLog = memberLog(spaceId, target.id(), "ROLE_REVOKE", subject,
                MemberRole.OWNER.name(), "ADMIN", ActionResult.SUCCESS, "所有权转移（原所有者降级）");
        repository.transferOwnership(spaceId, subject, target.subjectNo(), targetMemberId, MemberRole.ADMIN,
                grantLog, revokeLog);
        final SpaceMember newOwner = repository.findMemberById(targetMemberId)
                .orElseThrow(() -> memberRelationMissing());
        final SpaceMember formerOwner = repository.findActiveMembers(spaceId).stream()
                .filter(member -> subject.equals(member.subjectNo()))
                .findFirst()
                .orElseThrow(() -> memberRelationMissing());
        // 列口径出参取转移后回查值（load 的是转移前内存对象——移交②双处同步的回显须反映落库终态）
        final Space transferred = repository.findById(spaceId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                        SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE));
        return new OwnershipTransferOutcome(transferred.ownerSubjectNo(), formerOwner, newOwner, now);
    }

    /** 所有权转移结果（移交②双处同步的可视化：新 owner 列口径 + 成员表双行终态）。 */
    public record OwnershipTransferOutcome(String spaceOwnerSubjectNo, SpaceMember formerOwner,
            SpaceMember newOwner, LocalDateTime transferredAt) {
    }

    // ==== 内部 ====

    private Space load(final long spaceId) {
        return repository.findById(spaceId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                        SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE));
    }

    /**
     * 操作目标定位（Q5-A 行级）：成员行须属于该空间且活跃（否则 1006C0008 统一文案防成员存在性探测）。
     * OWNER 行判定由调用方统一走 denyOwnerProtected（拒绝留痕同口径——评审循环 1 修复批对齐）。
     */
    private SpaceMember locateOperableMember(final long memberId, final long spaceId) {
        final SpaceMember target = repository.findMemberById(memberId)
                .filter(member -> member.spaceId() == spaceId)
                .orElseThrow(() -> memberRelationMissing());
        if (target.status() != MemberStatus.ACTIVE) {
            throw memberRelationMissing();
        }
        return target;
    }

    private static SpaceBizException memberRelationMissing() {
        return new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
    }

    /** 写面越权拒绝留痕（DENIED，target=有成员行指 MEMBER、无成员行指 SPACE）+ 1006C0007（沿 3.2.3 deny 模式）。 */
    private void deny(final Space space, final String subject, final String action, final Long memberId,
            final String reasonNote) {
        final boolean memberScoped = memberId != null;
        repository.insertLog(new SpaceActionLog(null, space.id(),
                memberScoped ? TargetType.MEMBER : TargetType.SPACE,
                memberScoped ? memberId : space.id(), action,
                subject, null, null, ActionResult.DENIED,
                reasonNote != null ? reasonNote : SpaceErrorCodes.ACCESS_DENIED_LOG_REASON,
                LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    /** 唯一所有者保护拒绝留痕（DENIED）+ 1006C0009（行为 5 规则 1/3 显式门槛）。 */
    private void denyOwnerProtected(final Space space, final String subject, final String action,
            final Long memberId) {
        repository.insertLog(new SpaceActionLog(null, space.id(), TargetType.MEMBER, memberId, action,
                subject, MemberRole.OWNER.name(), null, ActionResult.DENIED,
                SpaceErrorCodes.OWNER_PROTECTED_MESSAGE, LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.OWNER_PROTECTED,
                SpaceErrorCodes.OWNER_PROTECTED_MESSAGE);
    }

    /** 读面拒绝留痕（非成员访问成员列表：对外 1006C0007，对内 ACCESS_DENIED——行为 6 规则 5/移交③）。 */
    private void denyRead(final Space space, final String subject) {
        repository.insertLog(new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(),
                "ACCESS_DENIED", subject, null, null, ActionResult.DENIED,
                SpaceErrorCodes.MEMBER_LIST_DENIED_LOG_REASON, LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    private SpaceActionLog memberLog(final long spaceId, final Long memberId, final String action,
            final String operator, final String fromValue, final String toValue, final ActionResult result,
            final String reason) {
        return new SpaceActionLog(null, spaceId, TargetType.MEMBER, memberId, action, operator,
                fromValue, toValue, result, reason, LocalDateTime.now(clock));
    }

    /** 理由长度门槛（超长走 1000C0001 通用通道——沿 3.2.3 教训）。 */
    private static void checkReason(final String reason) {
        if (reason.length() > REASON_MAX_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "理由超长（≤" + REASON_MAX_LENGTH + " 字符）");
        }
    }
}
