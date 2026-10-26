package com.ctds.connector.application;

import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorType;
import com.ctds.connector.domain.ConnectorUnavailableException;
import com.ctds.connector.domain.SubjectAdmission;
import com.ctds.connector.domain.SubjectAdmissionPort;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册申请与补正（规格行为 1 验收 1~4 / 行为 6 验收 1~3 / 行为 7 验收 4）：
 * 身份取鉴权上下文（请求体不含主体编号）；主体资格前置；同主体同名活跃档案防重（唯一索引 + 明确错误码）；
 * 申请单号 CA+6 位登记即签发；全动作留痕。不设幂等注解——防重由活跃态唯一索引天然承载（Q5-A）。
 */
@Service
public class ConnectorRegistrationService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    private static final int NAME_MIN_LENGTH = 2;
    private static final int NAME_MAX_LENGTH = 64;

    private final ConnectorRepository repository;
    private final SubjectAdmissionPort admissionPort;
    private final ConnectorAuditService audit;

    public ConnectorRegistrationService(final ConnectorRepository repository,
            final SubjectAdmissionPort admissionPort, final ConnectorAuditService audit) {
        this.repository = repository;
        this.admissionPort = admissionPort;
        this.audit = audit;
    }

    /** 提交注册申请（行为 6 验收 1/2/3）。 */
    @Transactional
    public ConnectorRegistration apply(final ConnectorRegistrationCommand command) {
        final String name = normalizeName(command.connectorName());
        final String type = normalizeType(command.connectorType());
        final String version = normalizeVersion(command.connectorVersion());
        requireAdmitted(command.subjectNo());
        if (repository.existsActiveName(command.subjectNo(), name, null)) {
            audit.denied(ConnectorAuditActions.DENIED, null, null, command.subjectNo(),
                    ConnectorErrorCodes.NAME_DUPLICATED.value(), "name=" + name);
            throw new ConnectorBizException(ConnectorErrorCodes.NAME_DUPLICATED,
                    ConnectorErrorCodes.NAME_DUPLICATED_MESSAGE);
        }
        final String applicationNo = repository.nextApplicationNo();
        try {
            repository.insert(new ConnectorRegistration(null, applicationNo, null, command.subjectNo(),
                    name, type, version, ConnectorStatus.PENDING_REVIEW, null, null, null, null, null));
        } catch (final DuplicateKeyException e) {
            audit.denied(ConnectorAuditActions.DENIED, null, null, command.subjectNo(),
                    ConnectorErrorCodes.NAME_DUPLICATED.value(), "name=" + name);
            throw new ConnectorBizException(ConnectorErrorCodes.NAME_DUPLICATED,
                    ConnectorErrorCodes.NAME_DUPLICATED_MESSAGE);
        }
        audit.success(ConnectorAuditActions.APPLY, applicationNo, null, command.subjectNo(),
                "name=" + name + ";type=" + type + ";version=" + version);
        return repository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new IllegalStateException("申请建档后读回失败"));
    }

    /** 补正（行为 7 验收 4：拒绝后补正重提；仅属主 + 仅待核验态）。 */
    @Transactional
    public ConnectorRegistration amend(final ConnectorAmendCommand command) {
        final ConnectorRegistration current = repository.findByApplicationNo(command.applicationNo())
                .filter(item -> item.subjectNo().equals(command.subjectNo()))
                .orElseThrow(() -> new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                        ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE));
        if (current.status() != ConnectorStatus.PENDING_REVIEW) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        final String name = normalizeName(command.connectorName());
        final String type = normalizeType(command.connectorType());
        final String version = normalizeVersion(command.connectorVersion());
        if (repository.existsActiveName(command.subjectNo(), name, command.applicationNo())) {
            throw new ConnectorBizException(ConnectorErrorCodes.NAME_DUPLICATED,
                    ConnectorErrorCodes.NAME_DUPLICATED_MESSAGE);
        }
        final int updated = repository.amend(command.applicationNo(), name, type, version);
        if (updated == 0) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        audit.success(ConnectorAuditActions.AMEND, command.applicationNo(), null, command.subjectNo(),
                "old=" + current.connectorName() + "/" + current.connectorType() + "/"
                        + current.connectorVersion() + ";new=" + name + "/" + type + "/" + version);
        return repository.findByApplicationNo(command.applicationNo())
                .orElseThrow(() -> new IllegalStateException("补正后读回失败"));
    }

    private void requireAdmitted(final String subjectNo) {
        final SubjectAdmission admission = admissionPort.check(subjectNo);
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.SUBJECT_UNAVAILABLE,
                    ConnectorErrorCodes.SUBJECT_UNAVAILABLE_MESSAGE);
        }
        if (admission != SubjectAdmission.ADMITTED) {
            audit.denied(ConnectorAuditActions.DENIED, null, null, subjectNo,
                    ConnectorErrorCodes.SUBJECT_NOT_ADMITTED.value(), "admission=" + admission.name());
            throw new ConnectorBizException(ConnectorErrorCodes.SUBJECT_NOT_ADMITTED,
                    ConnectorErrorCodes.SUBJECT_NOT_ADMITTED_MESSAGE);
        }
    }

    /** 要素校验（申请与核验第三查共用；文案为服务端常量）。 */
    public static String normalizeName(final String connectorName) {
        if (connectorName == null) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        final String name = connectorName.trim();
        if (name.length() < NAME_MIN_LENGTH || name.length() > NAME_MAX_LENGTH) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return name;
    }

    public static String normalizeType(final String connectorType) {
        if (!ConnectorType.supports(connectorType)) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return connectorType;
    }

    public static String normalizeVersion(final String connectorVersion) {
        if (connectorVersion == null || !VERSION_PATTERN.matcher(connectorVersion).matches()) {
            throw new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                    ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return connectorVersion;
    }
}
