package com.ctds.space.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.AccessMode;
import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.AdmissionStatus;
import com.ctds.space.domain.AdmissionType;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceAdmission;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 空间成员准入服务（WBS-3.2.4 hifi §3，规格行为 3 全部规则）：
 * 申请（公开/审批制空间）→ 待审批；邀请（邀请制空间）→ 待被邀方确认；确认/谢绝；审批通过/拒绝。
 * 门槛链：空间状态（须 ACTIVE，1006C0002 表意"空间不可接纳成员"，与"主体未入驻"1006C0001 两码区分）
 * → 形态匹配（1006C0011）→ 主体资格（ADMITTED，复用 3.2.3 通道）→ 重复幂等三态（Q3-A）。
 * 成员生效 = 仓储单事务三步（成员行 INSERT + 准入单乐观门槛更新回填 member_id + 留痕）。
 */
@Service
public class SpaceAdmissionService {

    /** 准入理由长度上限（space_admission.reason 列宽，3.2.2 口径）。 */
    private static final int REASON_MAX_LENGTH = 256;

    private final SpaceRepository repository;
    private final SpaceAccessGuard guard;
    private final SubjectAdmissionGate admissionGate;
    private final Clock clock;

    public SpaceAdmissionService(final SpaceRepository repository, final SpaceAccessGuard guard,
            final SubjectAdmissionGate admissionGate, final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.admissionGate = admissionGate;
        this.clock = clock;
    }

    // ==== 端点 1：提交加入申请（公开/审批制）====

    /**
     * 申请：登录主体对 OPEN/APPROVAL 空间提交加入申请 → PENDING_APPROVAL（不直接成为成员）。
     * 重复三态（Q3-A）：已是成员 → 返回既有成员关系；同型待处理单存在 → 返回既有单；终态单 → 允许新单。
     */
    public AdmissionOutcome apply(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireSpaceActive(space);
        requireAdmissionMode(space, AccessMode.OPEN, AccessMode.APPROVAL);
        admissionGate.requireAdmitted(subject);
        final AdmissionOutcome existing = resolveExisting(spaceId, subject, AdmissionType.APPLICATION);
        if (existing != null) {
            return existing;
        }
        final long id = repository.insertAdmission(new SpaceAdmission(null, spaceId, subject,
                AdmissionType.APPLICATION, AdmissionStatus.PENDING_APPROVAL, subject, null, null,
                LocalDateTime.now(clock), LocalDateTime.now(clock)),
                admissionLog(spaceId, null, "ADMIT_REQUEST", subject, null,
                        AdmissionStatus.PENDING_APPROVAL.name(), ActionResult.SUCCESS, null));
        return AdmissionOutcome.pending(loadAdmission(id));
    }

    // ==== 端点 2：发出邀请（邀请制）====

    /**
     * 邀请：owner/admin 对 INVITE 空间向被邀主体发出邀请 → PENDING_CONFIRMATION；
     * 被邀主体须 ADMITTED（提交时即拒，行为 3 规则 1，防枚举统一文案）。
     */
    public AdmissionOutcome invite(final long spaceId, final String inviteeSubjectNo, final String reason) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "ADMIT_INVITE");
        requireSpaceActive(space);
        requireAdmissionMode(space, AccessMode.INVITE);
        checkReason(reason);
        admissionGate.requireAdmitted(inviteeSubjectNo);
        final AdmissionOutcome existing = resolveExisting(spaceId, inviteeSubjectNo, AdmissionType.INVITATION);
        if (existing != null) {
            return existing;
        }
        final long id = repository.insertAdmission(new SpaceAdmission(null, spaceId, inviteeSubjectNo,
                AdmissionType.INVITATION, AdmissionStatus.PENDING_CONFIRMATION, subject, reason, null,
                LocalDateTime.now(clock), LocalDateTime.now(clock)),
                admissionLog(spaceId, null, "ADMIT_INVITE", subject, null,
                        AdmissionStatus.PENDING_CONFIRMATION.name(), ActionResult.SUCCESS, null));
        return AdmissionOutcome.pending(loadAdmission(id));
    }

    // ==== 端点 3：被邀方确认/谢绝 ====

    /**
     * 确认（CONFIRM → 成员生效事务）或谢绝（DECLINE → DECLINED）；仅被邀方本人（行为 3 规则 2
     * "被邀方确认"），他人操作 = 越权（1006C0007 + DENIED 留痕）。
     */
    public SpaceAdmission confirm(final long spaceId, final long admissionId, final boolean accept,
            final String reason) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        final SpaceAdmission admission = loadAdmissionInSpace(admissionId, spaceId);
        if (!subject.equals(admission.subjectNo())) {
            denyAdmission(space, subject, "ADMIT_CONFIRM", admissionId);
        }
        requireSpaceActive(space);
        if (admission.type() != AdmissionType.INVITATION) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_MODE_MISMATCH,
                    SpaceErrorCodes.ADMISSION_MODE_MISMATCH_MESSAGE);
        }
        // 终态自环显式前置门槛（3.2.3 同款教训：同值 WHERE 乐观门槛拦不住 APPROVED→APPROVED 再确认）
        if (admission.status() != AdmissionStatus.PENDING_CONFIRMATION) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_STATE_GATE,
                    SpaceErrorCodes.ADMISSION_STATE_GATE_MESSAGE);
        }
        checkReason(reason);
        if (!accept) {
            repository.appendAdmissionTransition(admissionId, spaceId, AdmissionStatus.PENDING_CONFIRMATION,
                    AdmissionStatus.DECLINED, admissionLog(spaceId, admissionId, "ADMIT_CONFIRM", subject,
                            AdmissionStatus.PENDING_CONFIRMATION.name(), AdmissionStatus.DECLINED.name(),
                            ActionResult.SUCCESS, reason));
            return loadAdmission(admissionId);
        }
        return activate(space, admission, "ADMIT_CONFIRM", subject, reason);
    }

    // ==== 端点 4：审批通过/拒绝 ====

    /**
     * 审批（APPROVE → 成员生效事务 / REJECT → REJECTED + 理由必填）；owner/admin（行为 3 规则 2）。
     */
    public SpaceAdmission approve(final long spaceId, final long admissionId, final boolean approve,
            final String reason) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "ADMIT_APPROVE");
        final SpaceAdmission admission = loadAdmissionInSpace(admissionId, spaceId);
        requireSpaceActive(space);
        if (admission.type() != AdmissionType.APPLICATION) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_MODE_MISMATCH,
                    SpaceErrorCodes.ADMISSION_MODE_MISMATCH_MESSAGE);
        }
        // 终态自环显式前置门槛（同上——拦住对已处理单的重复审批）
        if (admission.status() != AdmissionStatus.PENDING_APPROVAL) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_STATE_GATE,
                    SpaceErrorCodes.ADMISSION_STATE_GATE_MESSAGE);
        }
        if (!approve) {
            if (reason == null || reason.isBlank()) {
                // 拒绝理由必填属参数校验（400 通道），与移除理由同一口径——非准入单状态门槛（0010）
                throw new BizException(ErrorCodes.PARAM_INVALID, "拒绝申请须填写理由");
            }
            checkReason(reason);
            repository.appendAdmissionTransition(admissionId, spaceId, AdmissionStatus.PENDING_APPROVAL,
                    AdmissionStatus.REJECTED, admissionLog(spaceId, admissionId, "ADMIT_REJECT", subject,
                            AdmissionStatus.PENDING_APPROVAL.name(), AdmissionStatus.REJECTED.name(),
                            ActionResult.SUCCESS, reason));
            return loadAdmission(admissionId);
        }
        checkReason(reason);
        return activate(space, admission, "ADMIT_APPROVE", subject, reason);
    }

    // ==== 端点 5/6：准入单列表 ====

    /** 空间准入单列表（owner/admin 待办发现面；status 筛选可选，非法值 = 参数不合法 400）。 */
    public PageResult<SpaceAdmission> listBySpace(final long spaceId, final String status,
            final PageQuery page) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.canManage(space, repository.findActiveMembers(spaceId))) {
            denyAdmission(space, subject, "ACCESS_DENIED", null);
        }
        AdmissionStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = AdmissionStatus.valueOf(status);
            } catch (final IllegalArgumentException e) {
                throw new BizException(ErrorCodes.PARAM_INVALID, "准入单状态筛选取值非法");
            }
        }
        return repository.searchAdmissions(spaceId, statusFilter, page);
    }

    /** 我的准入单（个人视角：发出的申请 + 收到的邀请——剧本"我的邀请等价入口"）。 */
    public PageResult<SpaceAdmission> listMine(final PageQuery page) {
        final String subject = guard.requireSubject();
        return repository.searchAdmissionsBySubject(subject, page);
    }

    // ==== 内部 ====

    private Space load(final long spaceId) {
        return repository.findById(spaceId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                        SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE));
    }

    private SpaceAdmission loadAdmission(final long admissionId) {
        return repository.findAdmissionById(admissionId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.ADMISSION_STATE_GATE,
                        SpaceErrorCodes.ADMISSION_STATE_GATE_MESSAGE));
    }

    /** 准入单定位 + 空间归属校验（路径一致性双保险——防跨空间单据混淆，hifi §1 通用约定）。 */
    private SpaceAdmission loadAdmissionInSpace(final long admissionId, final long spaceId) {
        final SpaceAdmission admission = loadAdmission(admissionId);
        if (admission.spaceId() != spaceId) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                    SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE);
        }
        return admission;
    }

    /** 空间状态门槛（行为 3 规则 3：未启用/冻结/解散一律拒绝准入动作）。 */
    private static void requireSpaceActive(final Space space) {
        if (space.status() != SpaceStatus.ACTIVE) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
    }

    /** 形态匹配门槛（行为 3 规则 2：准入形态由参与方范围决定，错配 = 1006C0011）。 */
    private static void requireAdmissionMode(final Space space, final AccessMode... allowed) {
        boolean matched = false;
        for (final AccessMode mode : allowed) {
            matched = matched || space.accessMode() == mode;
        }
        if (!matched) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_MODE_MISMATCH,
                    SpaceErrorCodes.ADMISSION_MODE_MISMATCH_MESSAGE);
        }
    }

    /**
     * 重复准入幂等三态（Q3-A，行为 3 规则 4）：已是成员 → 返回既有成员关系；同型待处理单 → 返回既有单；
     * 终态单 → 允许新单（返回 null 继续创建）。
     */
    private AdmissionOutcome resolveExisting(final long spaceId, final String subjectNo,
            final AdmissionType type) {
        if (repository.existsActiveMembership(spaceId, subjectNo)) {
            final SpaceMember member = repository.findActiveMembers(spaceId).stream()
                    .filter(candidate -> subjectNo.equals(candidate.subjectNo()))
                    .findFirst()
                    .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                            SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE));
            return AdmissionOutcome.alreadyMember(member);
        }
        final Optional<SpaceAdmission> pending = repository.findPendingAdmission(spaceId, subjectNo, type);
        return pending.map(AdmissionOutcome::pending).orElse(null);
    }

    /** 成员生效（确认/审批通过共用）：仓储单事务三步（成员行 + 准入单 APPROVED 回填 + 留痕）。 */
    private SpaceAdmission activate(final Space space, final SpaceAdmission admission, final String action,
            final String operator, final String reason) {
        final LocalDateTime now = LocalDateTime.now(clock);
        final SpaceMember member = new SpaceMember(null, space.id(), admission.subjectNo(), MemberRole.MEMBER,
                MemberStatus.ACTIVE, now, null, now, now);
        repository.activateMembership(member, admission.id(), space.id(), admission.status(),
                admissionLog(space.id(), admission.id(), action, operator, admission.status().name(),
                        AdmissionStatus.APPROVED.name(), ActionResult.SUCCESS, reason));
        return loadAdmission(admission.id());
    }

    private void requireManagePermission(final Space space, final String subject, final String action) {
        if (!guard.canManage(space, repository.findActiveMembers(space.id()))) {
            denyAdmission(space, subject, action, null);
        }
    }

    /** 准入域拒绝留痕（DENIED，target=有单据指 ADMISSION、无单据指 SPACE）+ 1006C0007（沿 3.2.3 deny 模式）。 */
    private void denyAdmission(final Space space, final String subject, final String action,
            final Long admissionId) {
        final boolean admissionScoped = admissionId != null;
        repository.insertLog(new SpaceActionLog(null, space.id(),
                admissionScoped ? TargetType.ADMISSION : TargetType.SPACE,
                admissionScoped ? admissionId : space.id(), action,
                subject, null, null, ActionResult.DENIED,
                SpaceErrorCodes.ACCESS_DENIED_LOG_REASON, LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    private SpaceActionLog admissionLog(final long spaceId, final Long admissionId, final String action,
            final String operator, final String fromValue, final String toValue, final ActionResult result,
            final String reason) {
        return new SpaceActionLog(null, spaceId, TargetType.ADMISSION, admissionId, action, operator,
                fromValue, toValue, result, reason, LocalDateTime.now(clock));
    }

    /** 理由长度门槛（超长走 1000C0001 通用通道——沿 3.2.3"理由超长改走 common 处理器"教训）。 */
    private static void checkReason(final String reason) {
        if (reason != null && reason.length() > REASON_MAX_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID,
                    "理由超长（≤" + REASON_MAX_LENGTH + " 字符）");
        }
    }

    /**
     * 准入操作结果（Q3-A 幂等三态的响应形态）：alreadyMember=true 时 member 命中（返回既有成员关系），
     * 否则 admission 命中（新建或既有待处理单）。
     */
    public record AdmissionOutcome(boolean alreadyMember, SpaceAdmission admission, SpaceMember member) {

        public static AdmissionOutcome pending(final SpaceAdmission admission) {
            return new AdmissionOutcome(false, admission, null);
        }

        public static AdmissionOutcome alreadyMember(final SpaceMember member) {
            return new AdmissionOutcome(true, null, member);
        }
    }
}
