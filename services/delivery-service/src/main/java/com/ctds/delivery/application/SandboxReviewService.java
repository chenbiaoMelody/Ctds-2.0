package com.ctds.delivery.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultRepository;
import com.ctds.delivery.domain.SandboxResultReview;
import com.ctds.delivery.domain.SandboxResultStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore.ResultFile;
import com.ctds.delivery.interfaces.dto.SandboxViews.ResultView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 结果物审核域（WBS-3.5.5 hifi §3~§4）：状态机唯一写者。
 * 提交链 = 参数 → 可见性（0010）→ 需求方（0003）→ RUNNING（0011）→ 文件存在（0015）
 * → 大小上限（0008）→ 状态门槛（0016）→ 快照登记 → 留痕；
 * 审核链 = 治理档可见 → RUNNING → 登记 PENDING（0015/0016）→ 结论校验（0005）→ 落论不可改。
 * 结论一经作出不可修改；纠错 = 需求方重新提交触发新轮次（G2-A）。
 */
@Service
public class SandboxReviewService {

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int MAX_REASON_CHARS = 256;

    private final SandboxRepository sandboxRepository;
    private final SandboxResultRepository resultRepository;
    private final SandboxWorkspaceStore workspaceStore;
    private final SandboxLifecycleService lifecycle;
    private final SandboxResultPersistenceService persistence;
    private final SandboxAuditService audit;
    private final DeliveryProperties deliveryProperties;
    private final Clock clock;

    public SandboxReviewService(final SandboxRepository sandboxRepository,
            final SandboxResultRepository resultRepository,
            final SandboxWorkspaceStore workspaceStore, final SandboxLifecycleService lifecycle,
            final SandboxResultPersistenceService persistence, final SandboxAuditService audit,
            final DeliveryProperties deliveryProperties, final Clock clock) {
        this.sandboxRepository = sandboxRepository;
        this.resultRepository = resultRepository;
        this.workspaceStore = workspaceStore;
        this.lifecycle = lifecycle;
        this.persistence = persistence;
        this.audit = audit;
        this.deliveryProperties = deliveryProperties;
        this.clock = clock;
    }

    /** 需求方提交审核（幂等键 = sandboxNo:fileName:X-Idempotency-Key；重放返回首次登记）。 */
    @Idempotent(key = "#cmd.sandboxNo + ':' + #cmd.fileName + ':' + #cmd.idempotencyKey")
    public ResultView submit(final SandboxResultCommand cmd) {
        final String sandboxNo = cmd == null || cmd.sandboxNo() == null ? "" : cmd.sandboxNo();
        final String fileName = cmd == null ? null : cmd.fileName();
        final String requester = cmd == null || cmd.requesterNo() == null ? "" : cmd.requesterNo();
        try {
            final String name = requireFileName(fileName);
            requireText(requester);
            requireIdempotencyKey(cmd.idempotencyKey());

            final SandboxTask task = participantTask(sandboxNo, requester, name);
            if (!requester.equals(task.requesterNo())) {
                throw requesterNotAllowed();
            }
            lifecycle.ensureUsable(task);

            final ResultFile file = findResult(task.sandboxNo(), name)
                    .orElseThrow(SandboxReviewService::resultNotVisible);
            ensureWithinLimit(file);

            final Optional<SandboxResult> existing =
                    resultRepository.find(task.sandboxNo(), name);
            if (existing.isPresent() && existing.get().status() != SandboxResultStatus.REJECTED) {
                throw stateConflict();
            }
            final int round = existing.map(previous -> previous.reviewRound() + 1).orElse(1);
            final LocalDateTime now = LocalDateTime.now(clock);
            final SandboxResult registered = new SandboxResult(null, task.sandboxNo(), name,
                    SandboxResultStatus.PENDING, round, file.sizeBytes(), file.checksum(),
                    requester, now, null, null, null, cmd.idempotencyKey(), now, now);
            try {
                persistence.persistSubmission(registered, existing.isPresent());
            } catch (final DuplicateKeyException e) {
                // 并发首提：唯一键兜底，重读后按状态门槛拒绝
                throw stateConflict();
            }
            audit.jsonOnlyResult(SandboxAuditActions.RESULT_SUBMIT, task.sandboxNo(), name,
                    task.contractNo(), requester);
            return resultView(registered);
        } catch (final DeliveryBizException e) {
            audit.deniedResult(SandboxAuditActions.DENIED, sandboxNo, fileName, null, requester,
                    e.getErrorCode().value());
            throw e;
        }
    }

    /** 治理审核（运营方唯一写面；结论不可修改）。 */
    public ResultView review(final String sandboxNo, final String fileName,
            final String conclusion, final String reason, final String operatorNo) {
        final String rawSandboxNo = sandboxNo == null ? "" : sandboxNo;
        try {
            final String name = requireFileName(fileName);
            requireText(operatorNo);
            final SandboxTask task = sandboxRepository
                    .findBySandboxNo(safeSandboxNo(rawSandboxNo))
                    .orElseThrow(SandboxReviewService::taskNotVisible);
            lifecycle.ensureUsable(task);

            final SandboxResult registration = resultRepository
                    .find(task.sandboxNo(), name)
                    .orElseThrow(SandboxReviewService::resultNotVisible);
            if (registration.status() != SandboxResultStatus.PENDING) {
                throw stateConflict();
            }
            final boolean reject = requiresReject(conclusion);
            if (reject) {
                requireReason(reason);
            } else if (!"APPROVE".equals(trimmed(conclusion))) {
                throw paramInvalid();
            }
            final String rejectReason = reject ? reason.trim() : normalizeReason(reason);
            final LocalDateTime now = LocalDateTime.now(clock);
            final SandboxResultStatus next =
                    reject ? SandboxResultStatus.REJECTED : SandboxResultStatus.APPROVED;
            persistence.persistReview(
                    new SandboxResultReview(null, task.sandboxNo(), name,
                            registration.reviewRound(), next, rejectReason, operatorNo, now),
                    registration.concluded(next, operatorNo, now, rejectReason, now));
            audit.jsonOnlyResult(SandboxAuditActions.REVIEW, task.sandboxNo(), name,
                    task.contractNo(), operatorNo);
            return resultView(registration.concluded(next, operatorNo, now, rejectReason, now));
        } catch (final DeliveryBizException e) {
            audit.deniedResult(SandboxAuditActions.DENIED, rawSandboxNo, fileName, null,
                    operatorNo == null ? "" : operatorNo, e.getErrorCode().value());
            throw e;
        }
    }

    private SandboxTask participantTask(final String sandboxNo, final String callerNo,
            final String fileName) {
        final SandboxTask task = sandboxRepository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(SandboxReviewService::taskNotVisible);
        if (!callerNo.equals(task.requesterNo()) && !callerNo.equals(task.providerNo())) {
            throw taskNotVisible();
        }
        return task;
    }

    private Optional<ResultFile> findResult(final String sandboxNo, final String fileName) {
        return workspaceStore.listResults(sandboxNo).stream()
                .filter(file -> file.fileName().equals(fileName))
                .findFirst();
    }

    private void ensureWithinLimit(final ResultFile file) {
        // >64MiB 的文件 checksum 为 null（工作区口径）→ 与超上限同语义拒绝
        if (file.checksum() == null
                || file.sizeBytes() > deliveryProperties.getFile().getMaxBytes()) {
            throw fileTooLarge();
        }
    }

    private ResultView resultView(final SandboxResult registration) {
        final ResultFile file = findResult(registration.sandboxNo(), registration.fileName())
                .orElse(null);
        return ResultView.of(file, registration);
    }

    private static boolean requiresReject(final String conclusion) {
        return "REJECT".equals(trimmed(conclusion));
    }

    private static void requireReason(final String reason) {
        final String text = trimmed(reason);
        if (text == null || text.isEmpty() || text.length() > MAX_REASON_CHARS) {
            throw paramInvalid();
        }
    }

    private static String normalizeReason(final String reason) {
        final String text = trimmed(reason);
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_REASON_CHARS) {
            throw paramInvalid();
        }
        return text;
    }

    private static String trimmed(final String value) {
        return value == null ? null : value.trim();
    }

    private static String requireFileName(final String value) {
        final String text = value == null ? null : value.trim();
        if (text == null || !FILE_NAME.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw paramInvalid();
        }
        return value.trim();
    }

    private static String requireIdempotencyKey(final String value) {
        final String text = requireText(value);
        if (!IDEMPOTENCY_KEY.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static String safeSandboxNo(final String sandboxNo) {
        return sandboxNo == null || sandboxNo.isBlank() || sandboxNo.length() > 16 ? "" : sandboxNo;
    }

    private static SandboxBizException taskNotVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE_MESSAGE);
    }

    private static SandboxBizException resultNotVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_RESULT_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_RESULT_NOT_VISIBLE_MESSAGE);
    }

    private static SandboxBizException stateConflict() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_RESULT_STATE_CONFLICT,
                SandboxErrorCodes.SANDBOX_RESULT_STATE_CONFLICT_MESSAGE);
    }

    private static SandboxBizException requesterNotAllowed() {
        return new SandboxBizException(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
                DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE);
    }

    private static SandboxBizException fileTooLarge() {
        return new SandboxBizException(DeliveryErrorCodes.FILE_TOO_LARGE,
                DeliveryErrorCodes.FILE_TOO_LARGE_MESSAGE);
    }

    private static SandboxBizException paramInvalid() {
        return new SandboxBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
    }
}
