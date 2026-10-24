package com.ctds.delivery.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.ContractPort;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.JobExecResult;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxJob;
import com.ctds.delivery.domain.SandboxJobRepository;
import com.ctds.delivery.domain.SandboxJobStatus;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.domain.SandboxStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.interfaces.dto.SandboxViews.JobView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 受控作业执行（hifi §6）：一次作业 = 一次数据使用。准入顺序 = 可见性 → 状态 / 到期
 * → 作业清单 → 幂等 → 合约 check（放行才计数）→ 取号 → 执行 → 结论。
 * 拒绝路径零计数、零作业记录；作业 stdout/stderr 一律丢弃。
 */
@Service
public class SandboxJobService {

    private static final Logger log = LoggerFactory.getLogger(SandboxJobService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern JOB_ID = Pattern.compile("[a-z0-9-]{1,64}");
    private static final int MAX_ELEMENT_CHARS = 256;
    private static final String REASON_EXPIRED = "EXPIRED";

    private final SandboxRepository repository;
    private final SandboxJobRepository jobRepository;
    private final SandboxRuntime runtime;
    private final ContractPort contractPort;
    private final SandboxAuditService audit;
    private final SandboxProperties properties;
    private final Clock clock;

    public SandboxJobService(final SandboxRepository repository,
            final SandboxJobRepository jobRepository, final SandboxRuntime runtime,
            final ContractPort contractPort, final SandboxAuditService audit,
            final SandboxProperties properties, final Clock clock) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.runtime = runtime;
        this.contractPort = contractPort;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 执行受控作业（幂等键 = sandboxNo + X-Idempotency-Key；重放不重复计数）。 */
    @Idempotent(key = "#cmd.sandboxNo + ':' + #cmd.idempotencyKey")
    public JobView run(final SandboxRunCommand cmd) {
        final String sandboxNo = cmd == null ? "" : requireSandboxNo(cmd.sandboxNo());
        final String requester = cmd == null || cmd.requesterNo() == null ? "" : cmd.requesterNo();
        String jobNo = null;
        // 可见性拒绝在 visibleTask 内已留痕（带 contractNo 上下文），catch 兜底不得重复写行
        final AtomicBoolean traced = new AtomicBoolean(false);
        try {
            final String jobId = requireJobId(cmd.jobId());
            final String idempotencyKey = requireIdempotencyKey(cmd.idempotencyKey());
            final String purpose = validateElement(cmd.purpose());
            final String territory = validateElement(cmd.territory());

            final SandboxTask task = visibleTask(sandboxNo, requester, traced);
            if (!requester.equals(task.requesterNo())) {
                throw requesterNotAllowed();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            ensureUsable(task, now);

            final SandboxProperties.Job job = properties.findJob(jobId)
                    .orElseThrow(SandboxJobService::jobNotAllowed);

            final Optional<SandboxJob> replay =
                    jobRepository.findByIdempotencyKey(sandboxNo, idempotencyKey);
            if (replay.isPresent()) {
                return JobView.from(replay.get());
            }

            contractPort.check(task.contractNo(), requester, purpose, territory);

            jobNo = jobRepository.nextJobNo();
            final SandboxJob running = new SandboxJob(null, jobNo, sandboxNo, jobId,
                    SandboxJobStatus.RUNNING, null, null, purpose, territory, idempotencyKey, now,
                    null);
            jobRepository.insert(running);

            final JobExecResult result = runtime.exec(sandboxNo, job.getCommand(), job.getTimeout());
            final SandboxJobStatus status = statusOf(result);
            final LocalDateTime finishedAt = LocalDateTime.now(clock);
            jobRepository.finish(jobNo, status, result.exitCode(), result.durationMs(), finishedAt);
            final String finishedJobNo = jobNo;
            final SandboxJob fallback = new SandboxJob(null, finishedJobNo, sandboxNo, jobId,
                    status, result.exitCode(), result.durationMs(), purpose, territory,
                    idempotencyKey, now, finishedAt);
            if (status == SandboxJobStatus.SUCCEEDED) {
                audit.success(SandboxAuditActions.JOB, sandboxNo, finishedJobNo, task.contractNo(),
                        requester);
            } else {
                audit.failure(SandboxAuditActions.JOB, sandboxNo, finishedJobNo, task.contractNo(),
                        requester, status.name());
            }
            return jobRepository.findByJobNo(finishedJobNo).map(JobView::from)
                    .orElseGet(() -> JobView.from(fallback));
        } catch (final ContractPassThroughException e) {
            audit.denied(SandboxAuditActions.DENIED, sandboxNo, jobNo, null, requester, e.getCode());
            throw e;
        } catch (final DeliveryBizException e) {
            final String code = e.getErrorCode().value();
            if (jobNo != null && code.startsWith("1009S")) {
                jobRepository.finish(jobNo, SandboxJobStatus.FAILED, null, null,
                        LocalDateTime.now(clock));
                audit.failure(SandboxAuditActions.JOB, sandboxNo, jobNo, null, requester, code);
            } else if (!traced.get()) {
                audit.denied(SandboxAuditActions.DENIED, sandboxNo, jobNo, null, requester, code);
            }
            throw e;
        }
    }

    private void ensureUsable(final SandboxTask task, final LocalDateTime now) {
        if (!task.status().usable()) {
            throw stateConflict();
        }
        if (!task.expiresAt().isAfter(now)) {
            // 实时到期判定（到期扫描的兜底）：条件更新后同步停止容器，返回 409
            if (repository.close(task.sandboxNo(), SandboxStatus.EXPIRED, REASON_EXPIRED, now) > 0) {
                try {
                    runtime.stop(task.sandboxNo());
                } catch (final RuntimeException e) {
                    log.warn("到期容器停止失败（状态已终止）: sandboxNo={}", task.sandboxNo());
                }
                audit.success(SandboxAuditActions.EXPIRE, task.sandboxNo(), null, task.contractNo(),
                        task.requesterNo());
            }
            throw stateConflict();
        }
    }

    private SandboxTask visibleTask(final String sandboxNo, final String callerNo,
            final AtomicBoolean traced) {
        final SandboxTask task = repository.findBySandboxNo(sandboxNo).orElseThrow(() -> {
            audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, null, callerNo,
                    SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
            traced.set(true);
            return notVisible();
        });
        if (!callerNo.equals(task.requesterNo()) && !callerNo.equals(task.providerNo())) {
            audit.denied(SandboxAuditActions.DENIED, task.sandboxNo(), null, task.contractNo(),
                    callerNo, SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value());
            traced.set(true);
            throw notVisible();
        }
        return task;
    }

    private static SandboxJobStatus statusOf(final JobExecResult result) {
        if (result.timedOut() || result.exitCode() == JobExecResult.TIMEOUT_EXIT_CODE) {
            return SandboxJobStatus.TIMEOUT;
        }
        return result.succeeded() ? SandboxJobStatus.SUCCEEDED : SandboxJobStatus.FAILED;
    }

    private static String requireSandboxNo(final String value) {
        final String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 16) {
            throw notVisible();
        }
        return text;
    }

    private static String requireJobId(final String value) {
        if (value == null || !JOB_ID.matcher(value.trim()).matches()) {
            throw jobNotAllowed();
        }
        return value.trim();
    }

    private static String requireIdempotencyKey(final String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw paramInvalid();
        }
        return value.trim();
    }

    private static String validateElement(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_ELEMENT_CHARS) {
            throw paramInvalid();
        }
        return value;
    }

    private static SandboxBizException paramInvalid() {
        return new SandboxBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
    }

    private static SandboxBizException requesterNotAllowed() {
        return new SandboxBizException(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
                DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE);
    }

    private static SandboxBizException notVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE_MESSAGE);
    }

    private static SandboxBizException stateConflict() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_STATE_CONFLICT,
                SandboxErrorCodes.SANDBOX_STATE_CONFLICT_MESSAGE);
    }

    private static SandboxBizException jobNotAllowed() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_JOB_NOT_ALLOWED,
                SandboxErrorCodes.SANDBOX_JOB_NOT_ALLOWED_MESSAGE);
    }
}
