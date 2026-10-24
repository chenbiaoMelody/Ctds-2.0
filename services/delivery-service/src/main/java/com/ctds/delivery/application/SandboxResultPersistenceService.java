package com.ctds.delivery.application;

import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.SandboxActionLog;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultRepository;
import com.ctds.delivery.domain.SandboxResultReview;
import com.ctds.delivery.domain.SandboxResultReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果审核与导出事务边界（WBS-3.5.5 hifi §3~§5；沿 DeliveryPersistenceService 先例）：
 * 外部调用（合约 check）与加密包生成在事务外完成；落库 = 登记 / 审核留痕 / 导出记录
 * 与动作留痕同事务，任一失败整体回滚（包残留由调用方清理）。
 */
@Service
public class SandboxResultPersistenceService {

    private final SandboxRepository sandboxRepository;
    private final DeliveryRepository deliveryRepository;
    private final SandboxResultRepository resultRepository;
    private final SandboxResultReviewRepository reviewRepository;

    public SandboxResultPersistenceService(final SandboxRepository sandboxRepository,
            final DeliveryRepository deliveryRepository,
            final SandboxResultRepository resultRepository,
            final SandboxResultReviewRepository reviewRepository) {
        this.sandboxRepository = sandboxRepository;
        this.deliveryRepository = deliveryRepository;
        this.resultRepository = resultRepository;
        this.reviewRepository = reviewRepository;
    }

    /** 提交 / 重新提交落库（重新提交走条件更新；0 行 = 并发下状态已变 → 1009C0016）。 */
    @Transactional
    public void persistSubmission(final SandboxResult result, final boolean resubmission) {
        if (resubmission && resultRepository.resubmit(result) == 0) {
            throw stateConflict();
        }
        if (!resubmission) {
            resultRepository.insert(result);
        }
        sandboxRepository.insertActionLog(new SandboxActionLog(null, result.sandboxNo(), null,
                null, result.submittedBy(), SandboxAuditActions.RESULT_SUBMIT, "SUCCESS", null,
                result.fileName(), null));
    }

    /** 审核落库：审核留痕（不可变）+ 登记落论（仅 PENDING；0 行 = 已定论 → 1009C0016）。 */
    @Transactional
    public void persistReview(final SandboxResultReview review, final SandboxResult concluded) {
        reviewRepository.insert(review);
        if (resultRepository.conclude(concluded) == 0) {
            throw stateConflict();
        }
        sandboxRepository.insertActionLog(new SandboxActionLog(null, concluded.sandboxNo(), null,
                null, concluded.reviewedBy(), SandboxAuditActions.REVIEW, "SUCCESS", null,
                concluded.fileName(), null));
    }

    /** 导出记录 + 动作留痕同事务（包已先落盘，插入失败由调用方删包回滚）。 */
    @Transactional
    public void persistExport(final Delivery delivery) {
        deliveryRepository.insert(delivery);
        sandboxRepository.insertActionLog(new SandboxActionLog(null, delivery.sandboxNo(), null,
                delivery.contractNo(), delivery.requesterNo(), SandboxAuditActions.EXPORT,
                "SUCCESS", null, delivery.resultName(), null));
    }

    private static SandboxBizException stateConflict() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_RESULT_STATE_CONFLICT,
                SandboxErrorCodes.SANDBOX_RESULT_STATE_CONFLICT_MESSAGE);
    }
}
