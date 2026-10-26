package com.ctds.connector.application;

import com.ctds.common.errorcode.ErrorCode;
import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.BindingStatus;
import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorBindingRepository;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorLogRepository;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorUnavailableException;
import com.ctds.connector.domain.CurrentDid;
import com.ctds.connector.domain.DidStatusPort;
import com.ctds.connector.domain.SubjectAdmission;
import com.ctds.connector.domain.SubjectAdmissionPort;
import com.ctds.connector.domain.VerificationCheck;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册核验（规格行为 7 全条 / 行为 8 验收 1：核验通过建立绑定）。
 * <p>三查顺序：主体资格 → DID 有效 → 信息完整；任一不通过 = **200 业务结论**（verified=false + 首个失败项 + 留痕），
 * 状态保持待核验、可补正重提。跨域不可用 = **503**（不冒充业务结论）。通过时在同一事务内
 * 取号（CN）、转已注册、建 ACTIVE 绑定、写留痕。</p>
 */
@Service
public class ConnectorVerificationService {

    private final ConnectorRepository repository;
    private final ConnectorBindingRepository bindingRepository;
    private final ConnectorLogRepository logRepository;
    private final SubjectAdmissionPort admissionPort;
    private final DidStatusPort didStatusPort;
    private final ConnectorAuditService audit;

    public ConnectorVerificationService(final ConnectorRepository repository,
            final ConnectorBindingRepository bindingRepository, final ConnectorLogRepository logRepository,
            final SubjectAdmissionPort admissionPort, final DidStatusPort didStatusPort,
            final ConnectorAuditService audit) {
        this.repository = repository;
        this.bindingRepository = bindingRepository;
        this.logRepository = logRepository;
        this.admissionPort = admissionPort;
        this.didStatusPort = didStatusPort;
        this.audit = audit;
    }

    /** 执行核验（仅待核验态可核验；重复核验已注册档案 → 状态门槛 400）。 */
    @Transactional
    public VerificationOutcome verify(final String operatorNo, final String applicationNo) {
        final ConnectorRegistration current = repository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                        ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE));
        if (current.status() != ConnectorStatus.PENDING_REVIEW) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        final SubjectAdmission admission = admissionPort.check(current.subjectNo());
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.SUBJECT_UNAVAILABLE,
                    ConnectorErrorCodes.SUBJECT_UNAVAILABLE_MESSAGE);
        }
        if (admission != SubjectAdmission.ADMITTED) {
            return reject(current, operatorNo, VerificationCheck.SUBJECT_ADMISSION,
                    ConnectorErrorCodes.SUBJECT_NOT_ADMITTED, ConnectorErrorCodes.SUBJECT_NOT_ADMITTED_MESSAGE);
        }
        final CurrentDid currentDid = didStatusPort.current(current.subjectNo());
        if (currentDid.isUnavailable()) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.DID_UNAVAILABLE,
                    ConnectorErrorCodes.DID_UNAVAILABLE_MESSAGE);
        }
        if (!currentDid.active()) {
            return reject(current, operatorNo, VerificationCheck.DID_STATUS,
                    ConnectorErrorCodes.DID_NOT_VALID, didReason(currentDid.status()));
        }
        if (!complete(current)) {
            return reject(current, operatorNo, VerificationCheck.COMPLETENESS,
                    ConnectorErrorCodes.INFO_INCOMPLETE, ConnectorErrorCodes.INFO_INCOMPLETE_MESSAGE);
        }
        final String connectorNo = repository.nextConnectorNo();
        if (repository.markRegistered(current.applicationNo(), connectorNo) == 0) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        final ConnectorBinding binding = new ConnectorBinding(null, connectorNo, current.subjectNo(),
                currentDid.did(), BindingStatus.ACTIVE, LocalDateTime.now(), null);
        bindingRepository.insert(binding);
        audit.success(ConnectorAuditActions.VERIFY_APPROVED, current.applicationNo(), connectorNo,
                operatorNo, "did=" + currentDid.did() + ";checks=3/3");
        logRepository.attachConnectorNo(current.applicationNo(), connectorNo);
        final ConnectorRegistration registered = repository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new IllegalStateException("核验读到已注册档案失败"));
        return new VerificationOutcome(true, null, null, null, registered, binding);
    }

    private VerificationOutcome reject(final ConnectorRegistration current, final String operatorNo,
            final VerificationCheck check, final ErrorCode code, final String reason) {
        repository.markVerificationRejected(current.applicationNo(), code.value(), reason);
        audit.denied(ConnectorAuditActions.VERIFY_REJECTED, current.applicationNo(), current.connectorNo(),
                operatorNo, code.value(), "check=" + check.name() + ";reason=" + reason);
        return new VerificationOutcome(false, check, code.value(), reason, current, null);
    }

    private static boolean complete(final ConnectorRegistration current) {
        try {
            ConnectorRegistrationService.normalizeName(current.connectorName());
            ConnectorRegistrationService.normalizeType(current.connectorType());
            ConnectorRegistrationService.normalizeVersion(current.connectorVersion());
            return true;
        } catch (final ConnectorBizException e) {
            return false;
        }
    }

    private static String didReason(final String status) {
        return switch (status) {
            case CurrentDid.REVOKED -> ConnectorErrorCodes.DID_REVOKED_REASON;
            case CurrentDid.PENDING_ISSUE -> ConnectorErrorCodes.DID_PENDING_REASON;
            default -> ConnectorErrorCodes.DID_NONE_REASON;
        };
    }
}
