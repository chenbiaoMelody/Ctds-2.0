package com.ctds.delivery.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.CatalogPort;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.ContractPort;
import com.ctds.delivery.domain.ContractSummary;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.FileSource;
import com.ctds.delivery.domain.FileSourceRegistry;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.domain.SandboxSpec;
import com.ctds.delivery.domain.SandboxStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore;
import com.ctds.delivery.interfaces.dto.SandboxViews.SandboxView;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 沙箱申请开通与主动关闭（hifi §3）：固定顺序 = 幂等查重 → 合约只读摘要（参与方 + 生效）
 * → 发起人 = 需求方 → 产品形态（G1-A）→ 数据文件 → 并发配额 → 取号 → 工作区 → 容器
 * → 事务落库 → 审计。任一拒绝不建任务、不留半成品。
 */
@Service
public class SandboxCommandService {

    private static final Logger log = LoggerFactory.getLogger(SandboxCommandService.class);
    private static final Pattern CONTRACT_NO = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /** G1-A：沙箱只接受有数据载体的产品形态（API 形态拒绝 1009C0006）。 */
    private static final Set<String> SANDBOX_FORMS = Set.of("DATASET", "REPORT", "MODEL");
    private static final String REASON_CLOSED = "CLOSED";

    private final SandboxRepository repository;
    private final ContractPort contractPort;
    private final CatalogPort catalogPort;
    private final FileSourceRegistry fileSourceRegistry;
    private final SandboxWorkspaceStore workspaceStore;
    private final SandboxRuntime runtime;
    private final SandboxPersistenceService persistence;
    private final SandboxAuditService audit;
    private final SandboxProperties properties;
    private final Clock clock;

    public SandboxCommandService(final SandboxRepository repository, final ContractPort contractPort,
            final CatalogPort catalogPort, final FileSourceRegistry fileSourceRegistry,
            final SandboxWorkspaceStore workspaceStore, final SandboxRuntime runtime,
            final SandboxPersistenceService persistence, final SandboxAuditService audit,
            final SandboxProperties properties, final Clock clock) {
        this.repository = repository;
        this.contractPort = contractPort;
        this.catalogPort = catalogPort;
        this.fileSourceRegistry = fileSourceRegistry;
        this.workspaceStore = workspaceStore;
        this.runtime = runtime;
        this.persistence = persistence;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 申请开通（幂等键 = 发起人 + X-Idempotency-Key）。 */
    @Idempotent(key = "#cmd.requesterNo + ':' + #cmd.idempotencyKey")
    public SandboxView create(final SandboxCommand cmd) {
        final String rawRequester = cmd == null || cmd.requesterNo() == null ? "" : cmd.requesterNo();
        final String rawContract = cmd == null || cmd.contractNo() == null ? "" : cmd.contractNo();
        String sandboxNo = null;
        try {
            final String requester = requireText(rawRequester);
            final String contractNo = requireContractNo(rawContract);
            final String idempotencyKey = requireIdempotencyKey(cmd.idempotencyKey());

            final Optional<SandboxTask> replay =
                    repository.findByIdempotencyKey(requester, idempotencyKey);
            if (replay.isPresent()) {
                return SandboxView.from(replay.get());
            }

            final ContractSummary summary = contractPort.summarize(contractNo, requester);
            if (!requester.equals(summary.applicantNo())) {
                throw requesterNotAllowed();
            }
            final String productType = catalogPort.productType(summary.productId());
            if (!SANDBOX_FORMS.contains(productType)) {
                throw formMismatch();
            }
            final List<FileSource> files = fileSourceRegistry.listForProduct(summary.productId());
            if (files.isEmpty()) {
                throw runtimeUnavailable();
            }
            final LocalDateTime now = LocalDateTime.now(clock);
            if (repository.countActiveByRequester(requester, now)
                    >= properties.getQuota().getMaxActivePerRequester()
                    || repository.countActiveGlobal(now) >= properties.getQuota().getMaxActiveGlobal()) {
                throw quotaExceeded();
            }

            sandboxNo = repository.nextSandboxNo();
            final Path taskRoot = workspaceStore.prepare(sandboxNo, files);
            final SandboxTask task = buildTask(summary, requester, idempotencyKey, sandboxNo,
                    files.size(), now);
            runtime.launch(new SandboxSpec(sandboxNo, taskRoot.resolve("input"),
                    taskRoot.resolve("output"), properties.realJobsRoot(),
                    properties.getRuntime().getImage(), task.cpus(), task.memoryMb()));
            persistence.persistCreated(task);
            audit.jsonOnly(SandboxAuditActions.CREATE, sandboxNo, null, contractNo, requester);
            return repository.findBySandboxNo(sandboxNo).map(SandboxView::from)
                    .orElseGet(() -> SandboxView.from(task));
        } catch (final DuplicateKeyException e) {
            cleanup(sandboxNo);
            return replayAfterConflict(rawRequester, cmd == null ? null : cmd.idempotencyKey());
        } catch (final ContractPassThroughException e) {
            audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, rawContract, rawRequester,
                    e.getCode());
            throw e;
        } catch (final DeliveryBizException e) {
            cleanup(sandboxNo);
            final String code = e.getErrorCode().value();
            if (code.startsWith("1009S") || code.startsWith("1008C")) {
                audit.failure(SandboxAuditActions.CREATE, sandboxNo, null, rawContract, rawRequester,
                        code);
            } else {
                audit.denied(SandboxAuditActions.DENIED, sandboxNo, null, rawContract, rawRequester,
                        code);
            }
            throw e;
        } catch (final RuntimeException e) {
            cleanup(sandboxNo);
            audit.failure(SandboxAuditActions.CREATE, sandboxNo, null, rawContract, rawRequester,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED.value());
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE, e);
        }
    }

    /** 主动关闭（仅需求方；已终止任务幂等返回当前视图）。 */
    public SandboxView close(final String sandboxNo, final String callerNo) {
        final SandboxTask task = visibleTask(sandboxNo, callerNo);
        if (!callerNo.equals(task.requesterNo())) {
            final SandboxBizException ex = callerNo.equals(task.providerNo())
                    ? requesterNotAllowed() : notVisible();
            audit.denied(SandboxAuditActions.DENIED, task.sandboxNo(), null, task.contractNo(),
                    callerNo, ex.getErrorCode().value());
            throw ex;
        }
        if (!task.status().usable()) {
            audit.success(SandboxAuditActions.CLOSE, task.sandboxNo(), null, task.contractNo(),
                    callerNo);
            return SandboxView.from(task);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        if (repository.close(sandboxNo, SandboxStatus.STOPPED, REASON_CLOSED, now) == 0) {
            return repository.findBySandboxNo(sandboxNo).map(SandboxView::from)
                    .orElseGet(() -> SandboxView.from(task));
        }
        try {
            runtime.stop(sandboxNo);
        } catch (final RuntimeException e) {
            // 容器停止失败不改变业务结论：状态已置 STOPPED，容器由对账 / 运维兜底
            log.warn("沙箱容器停止失败（状态已终止）: sandboxNo={}", sandboxNo);
            audit.failure(SandboxAuditActions.CLOSE, task.sandboxNo(), null, task.contractNo(),
                    callerNo, SandboxErrorCodes.SANDBOX_OPERATION_FAILED.value());
            return repository.findBySandboxNo(sandboxNo).map(SandboxView::from)
                    .orElseGet(() -> SandboxView.from(task.closed(SandboxStatus.STOPPED,
                            REASON_CLOSED)));
        }
        audit.success(SandboxAuditActions.CLOSE, task.sandboxNo(), null, task.contractNo(), callerNo);
        return repository.findBySandboxNo(sandboxNo).map(SandboxView::from)
                .orElseGet(() -> SandboxView.from(task.closed(SandboxStatus.STOPPED, REASON_CLOSED)));
    }

    private SandboxTask buildTask(final ContractSummary summary, final String requester,
            final String idempotencyKey, final String sandboxNo, final int fileCount,
            final LocalDateTime now) {
        final long ttlSeconds = properties.getQuota().getTtl().toSeconds();
        return new SandboxTask(null, sandboxNo, summary.contractNo(), summary.productId(),
                summary.productName(), requester, summary.providerNo(), SandboxStatus.RUNNING,
                properties.getQuota().getCpus(), properties.getQuota().getMemoryMb(),
                (int) ttlSeconds, now.plusSeconds(ttlSeconds), fileCount, null, idempotencyKey, now);
    }

    private SandboxView replayAfterConflict(final String requester, final String idempotencyKey) {
        return repository.findByIdempotencyKey(requester, idempotencyKey)
                .map(SandboxView::from)
                .orElseThrow(() -> new SandboxBizException(
                        SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                        SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE));
    }

    private void cleanup(final String sandboxNo) {
        if (sandboxNo == null) {
            return;
        }
        try {
            runtime.stop(sandboxNo);
        } catch (final RuntimeException e) {
            log.warn("沙箱失败清理：容器停止未成功: sandboxNo={}", sandboxNo);
        }
        workspaceStore.deleteQuietly(sandboxNo);
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

    private static String safeSandboxNo(final String sandboxNo) {
        return sandboxNo == null || sandboxNo.isBlank() || sandboxNo.length() > 16 ? "" : sandboxNo;
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw paramInvalid();
        }
        return value.trim();
    }

    private static String requireContractNo(final String value) {
        final String text = requireText(value);
        if (!CONTRACT_NO.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static String requireIdempotencyKey(final String value) {
        final String text = requireText(value);
        if (!IDEMPOTENCY_KEY.matcher(text).matches()) {
            throw paramInvalid();
        }
        return text;
    }

    private static DeliveryBizException paramInvalid() {
        return new SandboxBizException(DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE);
    }

    private static SandboxBizException requesterNotAllowed() {
        return new SandboxBizException(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
                DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE);
    }

    private static SandboxBizException formMismatch() {
        return new SandboxBizException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
                DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH_MESSAGE);
    }

    private static SandboxBizException notVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE_MESSAGE);
    }

    private static SandboxBizException quotaExceeded() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_QUOTA_EXCEEDED,
                SandboxErrorCodes.SANDBOX_QUOTA_EXCEEDED_MESSAGE);
    }

    private static SandboxBizException runtimeUnavailable() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE,
                SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE);
    }
}
