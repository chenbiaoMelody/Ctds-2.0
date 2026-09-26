package com.ctds.subject.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.idempotency.Idempotent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 主体注册应用服务（规格 C-1.1 行为 1/4，WBS-3.1.2 hifi B3~B6）：
 * 注册（唯一性 + 防重复提交 + 申请编号）、撤销重报（lofi Q3-A：复用记录）、进度查询（脱敏）、
 * 入驻进度自助查询（CHG-C-1.1-V1.2 行为 8，双凭证）。
 * 防重复提交经 common-idempotency @Idempotent（幂等键 = 统一社会信用代码，ADR-007 模式 B）；
 * 唯一性双保险 = 服务层预检 + uk_uscc 唯一索引兜底。
 */
@Service
public class SubjectRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SubjectRegistrationService.class);
    private static final String ACTION_REGISTER = "subject.register";
    private static final String ACTION_CANCEL = "subject.cancel";
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 统一社会信用代码 18 位格式（GB 32100-2015 字符集）。 */
    private static final String USCC_REGEX = "^[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}$";
    private static final Pattern USCC_PATTERN = Pattern.compile(USCC_REGEX);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 联系电话双格式（CHG-C-1.1-V1.2 规格行为 1 第 6 条，hifi §2 规则表：手机 11 位 或 区号-座机）。 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(1[3-9]\\d{9}|0\\d{2,3}-\\d{7,8})$");
    private static final String PHONE_FORMAT_MESSAGE = "联系电话格式不正确（手机 11 位或 区号-座机）";
    private static final String ACTION_PROGRESS_QUERY = "subject.progress_query";
    private static final String PROGRESS_NOT_FOUND_MESSAGE = "未查询到匹配的申请";
    private static final int MAX_TEXT_CHARS = 256;

    private final SubjectRepository repository;
    private final SubjectStatusService statusService;
    private final SubjectOpsSupport ops;
    private final OwnershipGuard ownershipGuard;
    private final Clock clock;

    public SubjectRegistrationService(final SubjectRepository repository, final SubjectStatusService statusService,
            final SubjectOpsSupport ops, final OwnershipGuard ownershipGuard, final Clock clock) {
        this.repository = repository;
        this.statusService = statusService;
        this.ops = ops;
        this.ownershipGuard = ownershipGuard;
        this.clock = clock;
    }

    /**
     * 注册/重报（幂等键 = 统一社会信用代码：同一申请的重复点击/重放返回首次结果，不重复建档）。
     * 已存在主体：已驳回或待认证且已撤销 → 重报（更新可变信息、状态重置待认证；主体类型不随重报变更，
     * hifi 接口契约的重报可变字段集合不含主体类型）；
     * 其余状态 → 1004B0001"该主体已注册"（不泄露已有账号任何信息）。
     */
    @Idempotent(key = "#command.uscc")
    public RegistrationResult register(final RegisterCommand command) {
        requireValid(command);
        final String operator = ops.operator();
        final Optional<Subject> existing = repository.findByUscc(command.uscc());
        if (existing.isEmpty()) {
            return createRegistration(command, operator);
        }
        final Subject current = existing.get();
        if (current.status() == SubjectStatus.REJECTED) {
            return resubmit(current, command, operator, SubjectStatusService.RESUBMIT_AFTER_REJECT_REMARK,
                    SubjectStatus.REJECTED);
        }
        if (current.status() == SubjectStatus.PENDING_CERT && isCancelled(current.id())) {
            return resubmit(current, command, operator, SubjectStatusService.RESUBMIT_AFTER_CANCEL_REMARK,
                    SubjectStatus.PENDING_CERT);
        }
        ops.audit(operator, ACTION_REGISTER, current.subjectNo(), AuditOutcome.DENIED, "already_registered");
        throw new BizException(SubjectErrorCodes.SUBJECT_ALREADY_REGISTERED, "该主体已注册");
    }

    /** 撤销申请（仅待认证且未撤销过可撤销；归属断言 ADR-016 §2.6；撤销 = 撤销留痕，lofi Q3-A）。 */
    public CancellationResult cancel(final String subjectNo) {
        ops.requireSubjectNo(subjectNo);
        final Subject subject = repository.findBySubjectNo(subjectNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
        final String operator = ops.operator();
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_CANCEL);
        if (subject.status() != SubjectStatus.PENDING_CERT || isCancelled(subject.id())) {
            ops.audit(operator, ACTION_CANCEL, subjectNo, AuditOutcome.DENIED, "cancel_not_allowed");
            throw new BizException(SubjectErrorCodes.SUBJECT_CANCEL_NOT_ALLOWED, "当前状态不可撤销");
        }
        statusService.transition(subject.id(), SubjectStatus.PENDING_CERT, SubjectStatus.PENDING_CERT,
                TriggerRole.APPLICANT, operator, SubjectStatusService.CANCEL_REMARK);
        ops.audit(operator, ACTION_CANCEL, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("subject cancelled: subjectNo={}", subjectNo);
        return new CancellationResult(subjectNo, subject.status(), true);
    }

    /** 进度查询：注册信息（联系电话脱敏展示）+ 当前状态 + 全部流转留痕（规格行为 4 第 2 条；归属断言 §2.6）。 */
    public SubjectDetail detail(final String subjectNo) {
        ops.requireSubjectNo(subjectNo);
        final Subject subject = repository.findBySubjectNo(subjectNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
        ownershipGuard.requireOwnerOrReviewer(subject, "subject.read");
        return new SubjectDetail(subject, repository.findTransitions(subject.id()));
    }

    /**
     * 服务间内部只读：主体入驻状态（WBS-3.1.9 绑定核验用；hifi §5 实施修正 2026-09-22）。
     * 仅返回状态枚举——**最小暴露**（不含任何注册信息）；**不落归属断言**（内部只读面，沿 3.1.8
     * 签发面先例登记诚实边界；功能级权限由专用权限点 subject.internal.read 拦截——仅 did-internal
     * 持有，申请人/审核员均不持有，防按编号枚举状态，见评审②P2-1 收敛）。
     */
    public SubjectStatus admission(final String subjectNo) {
        ops.requireSubjectNo(subjectNo);
        return repository.findBySubjectNo(subjectNo)
                .map(Subject::status)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
    }

    /**
     * 入驻进度自助查询（CHG-C-1.1-V1.2 规格行为 8）：申请编号 + 统一社会信用代码双凭证，
     * 凭证断言取代归属断言（hifi §3 留痕口径）。编号不存在与信用代码不符统一出站同文案防枚举：
     * 仅编号不存在不落 DENIED（无对象可归属）；凭证不匹配落 DENIED（reason progress_denied）。
     */
    public ProgressResult progress(final String subjectNo, final String rawUscc) {
        ops.requireSubjectNo(subjectNo);
        final String uscc = rawUscc == null ? "" : rawUscc.trim().toUpperCase(Locale.ROOT);
        if (!USCC_PATTERN.matcher(uscc).matches()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "统一社会信用代码格式不正确");
        }
        final String operator = ops.operator();
        final Subject subject = repository.findBySubjectNo(subjectNo).orElse(null);
        if (subject == null) {
            throw new BizException(ErrorCodes.RESOURCE_NOT_FOUND, PROGRESS_NOT_FOUND_MESSAGE);
        }
        if (!subject.uscc().equals(uscc)) {
            ops.audit(operator, ACTION_PROGRESS_QUERY, subjectNo, AuditOutcome.DENIED, "progress_denied");
            throw new BizException(ErrorCodes.RESOURCE_NOT_FOUND, PROGRESS_NOT_FOUND_MESSAGE);
        }
        final String rejectReason = subject.status() == SubjectStatus.REJECTED
                ? repository.findLatestTransition(subject.id())
                        .filter(t -> t.toStatus() == SubjectStatus.REJECTED)
                        .map(StatusTransition::remark)
                        .orElse(null)
                : null;
        ops.audit(operator, ACTION_PROGRESS_QUERY, subjectNo, AuditOutcome.SUCCESS, null);
        return new ProgressResult(subject.subjectNo(), subject.subjectName(), subject.subjectType(),
                subject.status(), rejectReason);
    }

    private RegistrationResult createRegistration(final RegisterCommand command, final String operator) {
        final LocalDateTime now = LocalDateTime.now(clock);
        final String subjectNo = generateSubjectNo();
        final Subject subject = new Subject(null, subjectNo, command.subjectName(), command.uscc(),
                SubjectType.valueOf(command.subjectType()), command.regAddress(), command.contactName(),
                command.contactPhone(), command.adminAccount(), operator, SubjectStatus.PENDING_CERT, now, now);
        repository.create(subject, new StatusTransition(null, SubjectStatus.PENDING_CERT, TriggerRole.APPLICANT,
                operator, null, now));
        ops.audit(operator, ACTION_REGISTER, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("subject registered: subjectNo={}", subjectNo);
        return new RegistrationResult(subjectNo, SubjectStatus.PENDING_CERT);
    }

    private RegistrationResult resubmit(final Subject current, final RegisterCommand command, final String operator,
            final String remark, final SubjectStatus fromStatus) {
        final LocalDateTime now = LocalDateTime.now(clock);
        final Subject updated = new Subject(current.id(), current.subjectNo(), command.subjectName(), current.uscc(),
                current.subjectType(), command.regAddress(), command.contactName(),
                command.contactPhone(), command.adminAccount(), current.applicant(), SubjectStatus.PENDING_CERT,
                current.createdAt(), now);
        repository.resubmit(updated, new StatusTransition(fromStatus, SubjectStatus.PENDING_CERT,
                TriggerRole.APPLICANT, operator, remark, now));
        ops.audit(operator, ACTION_REGISTER, current.subjectNo(), AuditOutcome.SUCCESS, "resubmit");
        log.info("subject resubmitted: subjectNo={} from={}", current.subjectNo(), fromStatus);
        return new RegistrationResult(current.subjectNo(), SubjectStatus.PENDING_CERT);
    }

    /** 撤销标记判定：最新一条流转留痕 = 申请人撤销（契约口径见 SubjectStatusService.CANCEL_REMARK）。 */
    private boolean isCancelled(final long subjectId) {
        return repository.findLatestTransition(subjectId)
                .filter(t -> t.fromStatus() == SubjectStatus.PENDING_CERT
                        && t.toStatus() == SubjectStatus.PENDING_CERT
                        && t.triggerRole() == TriggerRole.APPLICANT
                        && SubjectStatusService.CANCEL_REMARK.equals(t.remark()))
                .isPresent();
    }

    /** 申请编号 = S + 日期 + 6 位当日序号（序号经 subject_daily_seq 行级原子自增取号）。
     *  取号日期经注入时钟（DB-09：与其他逻辑日期同源，禁直连系统钟——两把钟统一收口）。 */
    private String generateSubjectNo() {
        final LocalDate today = LocalDate.now(clock);
        return "S" + SEQ_DATE.format(today) + String.format("%06d", repository.nextDailySeq(today));
    }

    /** 业务规则校验（逐字段原因拼接，不产生半成品档案——规格行为 1 第 1 条；HTTP 路径另有 @Valid 前置）。 */
    private void requireValid(final RegisterCommand command) {
        final List<String> problems = new ArrayList<>();
        requireText(command.subjectName(), "主体名称", 128, problems);
        if (command.uscc() == null || !USCC_PATTERN.matcher(command.uscc()).matches()) {
            problems.add("统一社会信用代码格式不正确");
        }
        if (command.subjectType() == null || !isKnownSubjectType(command.subjectType())) {
            problems.add("主体类型不合法");
        }
        requireText(command.regAddress(), "注册地址", 256, problems);
        requireText(command.contactName(), "联系人姓名", 64, problems);
        if (command.contactPhone() == null || command.contactPhone().isBlank()) {
            problems.add("联系电话不能为空");
        } else if (!PHONE_PATTERN.matcher(command.contactPhone()).matches()) {
            problems.add(PHONE_FORMAT_MESSAGE);
        }
        if (command.adminAccount() == null || !ACCOUNT_PATTERN.matcher(command.adminAccount()).matches()) {
            problems.add("管理员账号不合法（仅允许字母数字 . _ -）");
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, String.join("；", problems));
        }
    }

    private void requireText(final String value, final String label, final int maxLength,
            final List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(label + "不能为空");
        } else if (value.length() > maxLength || value.length() > MAX_TEXT_CHARS) {
            problems.add(label + "超长（最长 " + maxLength + " 字符）");
        }
    }

    private boolean isKnownSubjectType(final String value) {
        for (final SubjectType type : SubjectType.values()) {
            if (type.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
