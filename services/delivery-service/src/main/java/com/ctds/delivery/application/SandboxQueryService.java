package com.ctds.delivery.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.DeliveryType;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxJobRepository;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultRepository;
import com.ctds.delivery.domain.SandboxResultReviewRepository;
import com.ctds.delivery.domain.SandboxStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore;
import com.ctds.delivery.interfaces.dto.DeliveryViews.ExportView;
import com.ctds.delivery.interfaces.dto.SandboxViews.JobView;
import com.ctds.delivery.interfaces.dto.SandboxViews.ResultView;
import com.ctds.delivery.interfaces.dto.SandboxViews.ReviewView;
import com.ctds.delivery.interfaces.dto.SandboxViews.SandboxView;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 沙箱读面（hifi §7.1 / 行为 4 规则 4、行为 7）：参与者详情 / 列表 / 作业历史 / 结果元数据
 * （含审核状态字段）/ 导出记录 / 治理读面读即留痕；数据面一律 403 + 留痕。
 * 非参与方与不存在同码同文（1009C0010）。
 */
@Service
public class SandboxQueryService {

    private final SandboxRepository repository;
    private final SandboxJobRepository jobRepository;
    private final SandboxResultRepository resultRepository;
    private final SandboxResultReviewRepository reviewRepository;
    private final DeliveryRepository deliveryRepository;
    private final SandboxWorkspaceStore workspaceStore;
    private final SandboxAuditService audit;

    public SandboxQueryService(final SandboxRepository repository,
            final SandboxJobRepository jobRepository,
            final SandboxResultRepository resultRepository,
            final SandboxResultReviewRepository reviewRepository,
            final DeliveryRepository deliveryRepository,
            final SandboxWorkspaceStore workspaceStore, final SandboxAuditService audit) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.reviewRepository = reviewRepository;
        this.deliveryRepository = deliveryRepository;
        this.workspaceStore = workspaceStore;
        this.audit = audit;
    }

    /** 参与者详情（需求方 / 提供方）。 */
    public SandboxView get(final String sandboxNo, final String callerNo) {
        final SandboxTask task = visibleTask(sandboxNo, callerNo);
        audit.success(SandboxAuditActions.READ, task.sandboxNo(), null, task.contractNo(), callerNo);
        return SandboxView.from(task);
    }

    /** 参与者列表（按调用者过滤；非参与方得到空列表，不泄露存在性）。 */
    public PageResult<SandboxView> list(final String callerNo, final String contractNo,
            final String status, final PageQuery page) {
        final SandboxStatus parsed = parseStatus(status);
        final List<SandboxView> list = repository.listForParticipant(callerNo, contractNo, parsed, page)
                .stream().map(SandboxView::from).toList();
        final long total = repository.countForParticipant(callerNo, contractNo, parsed);
        audit.success(SandboxAuditActions.READ, null, null, contractNo, callerNo);
        return PageResult.of(list, total, page);
    }

    /** 作业历史（参与者；只出状态 / 退出码 / 耗时）。 */
    public PageResult<JobView> jobs(final String sandboxNo, final String callerNo,
            final PageQuery page) {
        final SandboxTask task = visibleTask(sandboxNo, callerNo);
        final List<JobView> list = jobRepository.listBySandbox(task.sandboxNo(), page).stream()
                .map(JobView::from).toList();
        final long total = jobRepository.countBySandbox(task.sandboxNo());
        audit.success(SandboxAuditActions.READ, task.sandboxNo(), null, task.contractNo(), callerNo);
        return PageResult.of(list, total, page);
    }

    /** 结果物元数据（参与者；无内容、无路径；含审核状态 / 结论 / 驳回理由 / 轮次，未提交为 null）。 */
    public List<ResultView> results(final String sandboxNo, final String callerNo) {
        final SandboxTask task = visibleTask(sandboxNo, callerNo);
        audit.success(SandboxAuditActions.READ, task.sandboxNo(), null, task.contractNo(), callerNo);
        final Map<String, SandboxResult> registrations =
                resultRepository.findBySandbox(task.sandboxNo()).stream()
                        .collect(Collectors.toMap(SandboxResult::fileName, Function.identity(),
                                (left, right) -> left));
        return workspaceStore.listResults(task.sandboxNo()).stream()
                .map(file -> ResultView.of(file, registrations.get(file.fileName())))
                .toList();
    }

    /** 导出记录列表（参与者 = 需求方 / 提供方；分页；只出业务要素与校验值）。 */
    public PageResult<ExportView> exports(final String sandboxNo, final String callerNo,
            final PageQuery page) {
        final SandboxTask task = visibleTask(sandboxNo, callerNo);
        final List<ExportView> list = deliveryRepository
                .listBySandbox(task.sandboxNo(), DeliveryType.SANDBOX_EXPORT, page).stream()
                .map(ExportView::from).toList();
        final long total = deliveryRepository.countBySandbox(task.sandboxNo(),
                DeliveryType.SANDBOX_EXPORT);
        audit.success(SandboxAuditActions.READ, task.sandboxNo(), null, task.contractNo(), callerNo);
        return PageResult.of(list, total, page);
    }

    /** 治理结果物元数据（运营方；读即留痕；无内容、无路径；含审核状态 / 结论 / 驳回理由 / 轮次）。 */
    public List<ResultView> governanceResults(final String sandboxNo, final String operatorNo) {
        final SandboxTask task = repository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(() -> {
                    audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, null, operatorNo,
                            SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
                    return notVisible();
                });
        audit.success(SandboxAuditActions.GOVERNANCE_READ, task.sandboxNo(), null,
                task.contractNo(), operatorNo);
        final Map<String, SandboxResult> registrations =
                resultRepository.findBySandbox(task.sandboxNo()).stream()
                        .collect(Collectors.toMap(SandboxResult::fileName, Function.identity(),
                                (left, right) -> left));
        return workspaceStore.listResults(task.sandboxNo()).stream()
                .map(file -> ResultView.of(file, registrations.get(file.fileName())))
                .toList();
    }

    /** 治理审核留痕列表（运营方；读即留痕；按文件 + 轮次返回）。 */
    public PageResult<ReviewView> governanceReviews(final String sandboxNo, final String fileName,
            final PageQuery page, final String operatorNo) {
        final SandboxTask task = repository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(() -> {
                    audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, null, operatorNo,
                            SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
                    return notVisible();
                });
        final List<ReviewView> list = reviewRepository
                .listBySandbox(task.sandboxNo(), fileName, page).stream()
                .map(ReviewView::from).toList();
        final long total = reviewRepository.countBySandbox(task.sandboxNo(), fileName);
        audit.success(SandboxAuditActions.GOVERNANCE_READ, task.sandboxNo(), null,
                task.contractNo(), operatorNo);
        return PageResult.of(list, total, page);
    }

    /** 治理详情（运营方；读即留痕）。 */
    public SandboxView governanceGet(final String sandboxNo, final String operatorNo) {
        final SandboxTask task = repository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(() -> {
                    audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, null, operatorNo,
                            SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
                    return notVisible();
                });
        audit.success(SandboxAuditActions.GOVERNANCE_READ, task.sandboxNo(), null,
                task.contractNo(), operatorNo);
        return SandboxView.from(task);
    }

    /** 治理列表（运营方；读即留痕）。 */
    public PageResult<SandboxView> governanceList(final String contractNo, final String status,
            final PageQuery page, final String operatorNo) {
        final SandboxStatus parsed = parseStatus(status);
        final List<SandboxView> list = repository.listGovernance(contractNo, parsed, page).stream()
                .map(SandboxView::from).toList();
        final long total = repository.countGovernance(contractNo, parsed);
        audit.success(SandboxAuditActions.GOVERNANCE_READ, null, null, contractNo, operatorNo);
        return PageResult.of(list, total, page);
    }

    /** 数据面直取：一律 403 + 留痕（原始数据与产物内容都不可直取）。 */
    public void denyDataAccess(final String sandboxNo, final String callerNo) {
        String contractNo = null;
        boolean participant = false;
        try {
            final SandboxTask task = repository.findBySandboxNo(safeSandboxNo(sandboxNo)).orElse(null);
            if (task != null) {
                contractNo = task.contractNo();
                participant = callerNo.equals(task.requesterNo())
                        || callerNo.equals(task.providerNo());
            }
        } catch (final RuntimeException e) {
            participant = false;
        }
        audit.denied(SandboxAuditActions.DATA_DENIED, sandboxNo, null, contractNo, callerNo,
                SandboxErrorCodes.SANDBOX_DATA_DENIED.value());
        if (!participant) {
            throw notVisible();
        }
        throw new SandboxBizException(SandboxErrorCodes.SANDBOX_DATA_DENIED,
                SandboxErrorCodes.SANDBOX_DATA_DENIED_MESSAGE);
    }

    private SandboxTask visibleTask(final String sandboxNo, final String callerNo) {
        final SandboxTask task = repository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(() -> {
                    audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, null, callerNo,
                            SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
                    return notVisible();
                });
        if (!callerNo.equals(task.requesterNo()) && !callerNo.equals(task.providerNo())) {
            audit.denied(SandboxAuditActions.DENIED, task.sandboxNo(), null, task.contractNo(),
                    callerNo, SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
            throw notVisible();
        }
        return task;
    }

    private static SandboxStatus parseStatus(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SandboxStatus.valueOf(value.trim());
        } catch (final IllegalArgumentException e) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                    DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
        }
    }

    private static String safeSandboxNo(final String sandboxNo) {
        return sandboxNo == null || sandboxNo.isBlank() || sandboxNo.length() > 16 ? "" : sandboxNo;
    }

    private static SandboxBizException notVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE_MESSAGE);
    }
}
