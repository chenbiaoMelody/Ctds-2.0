package com.ctds.subject.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.TriggerRole;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 平台人工审核应用服务（WBS-3.1.5，规格 C-1.1 行为 5）：
 * 待审核清单（仅授权角色）/ 通过（→ 已入驻）/ 驳回（理由必填 → 已驳回）。
 * 流转一律经 SubjectStatusService.transition()（ADR-016 §3：不建平行流转通道；乐观门槛兜并发，
 * 并发双审核恰一人成功）；审核结论与操作者、时间随流转留痕闭环（行为 5 第 4 条）。
 * 权限即准入：本服务端点仅 subject.review 授权角色可达（ReviewController 注解强制），
 * 不走 OwnershipGuard（对象级归属断言是申请人侧端点口径，3.1.3 既有实现不变）。
 */
@Service
public class ReviewService {

    /** 审计动作（沿认证域命名，WBS-3.1.5 hifi 接口契约；测试断言以本常量为唯一口径）。 */
    static final String ACTION_REVIEW_APPROVE = "certification.review.approve";
    static final String ACTION_REVIEW_REJECT = "certification.review.reject";

    static final String APPROVE_REMARK = "审核通过";
    static final String REJECT_REMARK_PREFIX = "审核驳回：";
    static final String REASON_REQUIRED_MESSAGE = "驳回理由必填";

    private final SubjectRepository subjectRepository;
    private final SubjectStatusService statusService;
    private final SubjectOpsSupport ops;
    private final CertificationProperties properties;

    public ReviewService(final SubjectRepository subjectRepository, final SubjectStatusService statusService,
            final SubjectOpsSupport ops, final CertificationProperties properties) {
        this.subjectRepository = subjectRepository;
        this.statusService = statusService;
        this.ops = ops;
        this.properties = properties;
    }

    /** 待审核清单（行为 5 第 1 条前半）：固定 PENDING_REVIEW 过滤，分页返回。 */
    public PageResult<ReviewQueueItem> queue(final PageQuery query) {
        final long total = subjectRepository.countByStatus(SubjectStatus.PENDING_REVIEW);
        if (total == 0) {
            return PageResult.empty(query);
        }
        final List<Subject> rows = subjectRepository.findByStatus(SubjectStatus.PENDING_REVIEW,
                (int) query.offset(), query.pageSize());
        final List<ReviewQueueItem> items = rows.stream()
                .map(subject -> new ReviewQueueItem(subject.subjectNo(), subject.subjectName(),
                        subject.subjectType().name(), subject.createdAt()))
                .toList();
        return PageResult.of(items, total, query);
    }

    /** 通过（行为 5 第 2 条）：待审核 → 已入驻，留痕"审核通过"。 */
    public ReviewActionResult approve(final String subjectNo) {
        final Subject subject = requireSubject(subjectNo);
        requirePendingReview(subject, ACTION_REVIEW_APPROVE);
        statusService.transition(subject.id(), SubjectStatus.PENDING_REVIEW, SubjectStatus.ADMITTED,
                TriggerRole.REVIEWER, ops.operator(), APPROVE_REMARK);
        ops.audit(ops.operator(), ACTION_REVIEW_APPROVE, subjectNo, AuditOutcome.SUCCESS, null);
        return new ReviewActionResult(subjectNo, SubjectStatus.ADMITTED.name());
    }

    /** 驳回（行为 5 第 2 条）：理由必填 + 长度上限（配置参数），理由随留痕备注落库。 */
    public ReviewActionResult reject(final String subjectNo, final String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, REASON_REQUIRED_MESSAGE);
        }
        final int maxLength = properties.getReviewReasonMaxLength();
        if (reason.length() > maxLength) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "驳回理由长度不能超过" + maxLength + "字");
        }
        final Subject subject = requireSubject(subjectNo);
        requirePendingReview(subject, ACTION_REVIEW_REJECT);
        statusService.transition(subject.id(), SubjectStatus.PENDING_REVIEW, SubjectStatus.REJECTED,
                TriggerRole.REVIEWER, ops.operator(), REJECT_REMARK_PREFIX + reason);
        ops.audit(ops.operator(), ACTION_REVIEW_REJECT, subjectNo, AuditOutcome.SUCCESS, null);
        return new ReviewActionResult(subjectNo, SubjectStatus.REJECTED.name());
    }

    /** 申请编号不存在时与"归属不匹配"出站同形（1000C0003，防存在性探测，ADR-016 §2.6 口径）。 */
    private Subject requireSubject(final String subjectNo) {
        ops.requireSubjectNo(subjectNo);
        return subjectRepository.findBySubjectNo(subjectNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
    }

    /**
     * 状态前置门槛（业务文案口径：审核操作）：非待审核即拒绝并落 DENIED 审计（hifi E3，
     * 沿认证域拒绝留痕先例）；并发窗口由 transition 的乐观状态门槛兜底（两审核同时提交时
     * 仅一人成功，败者 1004C0002——WBS-3.1.5 hifi E4）。
     */
    private void requirePendingReview(final Subject subject, final String action) {
        if (subject.status() != SubjectStatus.PENDING_REVIEW) {
            ops.audit(ops.operator(), action, subject.subjectNo(), AuditOutcome.DENIED, "state_not_allowed");
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行审核操作");
        }
    }
}
