package com.ctds.delivery.application;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.idempotency.Idempotent;
import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.ContractPort;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.DeliveryType;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultRepository;
import com.ctds.delivery.domain.SandboxResultStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.FilePackageStore;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore.ResultFile;
import com.ctds.delivery.interfaces.dto.DeliveryViews.ExportView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 审批后导出（WBS-3.5.5 hifi §5）= 一次交付动作：唯一内容出口。
 * 链 = 参数 → 可见性（0010）→ 需求方（0003）→ RUNNING（0011）→ 登记 APPROVED（0016）
 * → 合约 check（放行即计数；越界原码透传零计数零记录零包）→ 快照复核（审 A 交 B → 0016）
 * → 受控读取 → SM4 加密包 → 交付记录（SANDBOX_EXPORT）→ 留痕。
 * check 放行后的平台侧失败不写导出记录 + 审计 FAILURE，不改写已发生的计数（诚实边界）。
 */
@Service
public class SandboxExportService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExportService.class);
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int MAX_ELEMENT_CHARS = 256;
    private static final String RESULT_SUCCESS = "SUCCESS";

    private final SandboxRepository sandboxRepository;
    private final SandboxResultRepository resultRepository;
    private final DeliveryRepository deliveryRepository;
    private final ContractPort contractPort;
    private final SandboxWorkspaceStore workspaceStore;
    private final FilePackageStore packageStore;
    private final Sm4Service sm4Service;
    private final Sm3Service sm3Service;
    private final SandboxLifecycleService lifecycle;
    private final SandboxResultPersistenceService persistence;
    private final SandboxAuditService audit;
    private final DeliveryProperties properties;
    private final Clock clock;

    public SandboxExportService(final SandboxRepository sandboxRepository,
            final SandboxResultRepository resultRepository,
            final DeliveryRepository deliveryRepository, final ContractPort contractPort,
            final SandboxWorkspaceStore workspaceStore, final FilePackageStore packageStore,
            final Sm4Service sm4Service, final Sm3Service sm3Service,
            final SandboxLifecycleService lifecycle,
            final SandboxResultPersistenceService persistence, final SandboxAuditService audit,
            final DeliveryProperties properties, final Clock clock) {
        this.sandboxRepository = sandboxRepository;
        this.resultRepository = resultRepository;
        this.deliveryRepository = deliveryRepository;
        this.contractPort = contractPort;
        this.workspaceStore = workspaceStore;
        this.packageStore = packageStore;
        this.sm4Service = sm4Service;
        this.sm3Service = sm3Service;
        this.lifecycle = lifecycle;
        this.persistence = persistence;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 需求方导出（幂等键 = requester:X-Idempotency-Key；deliveries 唯一键兜底）。 */
    @Idempotent(key = "#cmd.requesterNo + ':' + #cmd.idempotencyKey")
    public ExportView export(final SandboxExportCommand cmd) {
        final String sandboxNo = cmd == null || cmd.sandboxNo() == null ? "" : cmd.sandboxNo();
        final String fileName = cmd == null ? null : cmd.fileName();
        final String requester = cmd == null || cmd.requesterNo() == null ? "" : cmd.requesterNo();
        // check 放行后的平台侧失败 = FAILURE（不改写已发生计数），此前拒绝 = DENIED
        final AtomicBoolean checkCounted = new AtomicBoolean(false);
        try {
            final String name = requireFileName(fileName);
            requireText(requester);
            requireIdempotencyKey(cmd.idempotencyKey());
            final String purpose = validateElement(cmd.purpose());
            final String territory = validateElement(cmd.territory());

            final SandboxTask task = participantTask(sandboxNo, requester, name);
            if (!requester.equals(task.requesterNo())) {
                throw requesterNotAllowed();
            }
            lifecycle.ensureUsable(task);

            final SandboxResult registration = resultRepository.find(task.sandboxNo(), name)
                    .orElseThrow(SandboxExportService::stateConflict);
            if (registration.status() != SandboxResultStatus.APPROVED) {
                throw stateConflict();
            }

            contractPort.check(task.contractNo(), requester, purpose, territory);
            checkCounted.set(true);

            // 快照复核：防"审核版本 A、导出版本 B"（文件被覆盖 / 删除均拒绝，须重新提交审核）
            final ResultFile current = workspaceStore.listResults(task.sandboxNo()).stream()
                    .filter(file -> file.fileName().equals(name))
                    .findFirst()
                    .orElseThrow(SandboxExportService::stateConflict);
            if (current.sizeBytes() != registration.sizeBytes()
                    || !Objects.equals(current.checksum(), registration.checksum())) {
                throw stateConflict();
            }
            final byte[] plaintext = workspaceStore.readResult(task.sandboxNo(), name);
            if (plaintext.length != registration.sizeBytes()) {
                throw stateConflict();
            }
            // 实际导出字节重算 SM3 必须等于登记快照：复核与读取之间的等长替换同样拒绝（纵深）
            if (!sm3Service.digestHex(plaintext).equals(registration.checksum())) {
                throw stateConflict();
            }
            if (plaintext.length > properties.getFile().getMaxBytes()) {
                throw fileTooLarge();
            }

            final String deliveryNo = deliveryRepository.nextDeliveryNo();
            final byte[] envelope = encrypt(plaintext);
            final String packageId = deliveryNo + ".sm4";
            packageStore.store(packageId, envelope);
            final LocalDateTime now = LocalDateTime.now(clock);
            final Delivery delivery = new Delivery(null, deliveryNo, task.contractNo(),
                    task.productId(), task.productName(), DeliveryType.SANDBOX_EXPORT, requester,
                    task.providerNo(), null, name, registration.sizeBytes(), packageId,
                    registration.checksum(), properties.getFile().getKeyRef(), RESULT_SUCCESS,
                    cmd.idempotencyKey(), task.sandboxNo(), name, now);
            try {
                persistence.persistExport(delivery);
            } catch (final DuplicateKeyException e) {
                // 幂等兜底：同键并发/重放越过内存层 → 删包并返回首次导出记录
                packageStore.deleteQuietly(packageId);
                return deliveryRepository.findByIdempotencyKey(requester, cmd.idempotencyKey())
                        .map(ExportView::from)
                        .orElseThrow(() -> packageFailed(e));
            } catch (final RuntimeException e) {
                packageStore.deleteQuietly(packageId);
                throw packageFailed(e);
            }
            audit.jsonOnlyResult(SandboxAuditActions.EXPORT, task.sandboxNo(), name,
                    task.contractNo(), requester);
            return deliveryRepository.findByDeliveryNo(deliveryNo).map(ExportView::from)
                    .orElseGet(() -> ExportView.from(delivery));
        } catch (final ContractPassThroughException e) {
            audit.deniedResult(SandboxAuditActions.DENIED, sandboxNo, fileName, null, requester,
                    e.getCode());
            throw e;
        } catch (final DeliveryBizException e) {
            final String code = e.getErrorCode().value();
            if (checkCounted.get() && code.startsWith("1009S")) {
                audit.failureResult(SandboxAuditActions.EXPORT, sandboxNo, fileName, null,
                        requester, code);
            } else {
                audit.deniedResult(SandboxAuditActions.DENIED, sandboxNo, fileName, null,
                        requester, code);
            }
            throw e;
        }
    }

    private SandboxTask participantTask(final String sandboxNo, final String callerNo,
            final String fileName) {
        final SandboxTask task = sandboxRepository.findBySandboxNo(safeSandboxNo(sandboxNo))
                .orElseThrow(() -> taskNotVisible());
        if (!callerNo.equals(task.requesterNo()) && !callerNo.equals(task.providerNo())) {
            throw taskNotVisible();
        }
        return task;
    }

    private byte[] encrypt(final byte[] plaintext) {
        try {
            return sm4Service.encrypt(plaintext, properties.getFile().getKeyRef());
        } catch (final RuntimeException e) {
            log.error("导出包加密失败");
            throw packageFailed(e);
        }
    }

    private static DeliveryBizException packageFailed(final Throwable cause) {
        return new SandboxBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, cause);
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

    private static String validateElement(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_ELEMENT_CHARS) {
            throw paramInvalid();
        }
        return value;
    }

    private static String safeSandboxNo(final String sandboxNo) {
        return sandboxNo == null || sandboxNo.isBlank() || sandboxNo.length() > 16 ? "" : sandboxNo;
    }

    private static SandboxBizException taskNotVisible() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_NOT_VISIBLE,
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE_MESSAGE);
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
