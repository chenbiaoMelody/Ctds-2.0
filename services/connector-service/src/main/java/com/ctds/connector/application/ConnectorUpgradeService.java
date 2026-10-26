package com.ctds.connector.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.Compatibility;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorVersionChange;
import com.ctds.connector.domain.ConnectorVersionChangeRepository;
import com.ctds.connector.domain.VersionChangeStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 升级与版本兼容（规格行为 9）：升级 = 平台侧登记动作，不含运行中实例在线热升级。
 * 兼容 → 同事务原子替换版本；不兼容 → 待确认记录（当前版本不动），运营方显式确认后替换。
 */
@Service
public class ConnectorUpgradeService {

    private final ConnectorRepository repository;
    private final ConnectorVersionChangeRepository changeRepository;
    private final ConnectorAuditService audit;

    public ConnectorUpgradeService(final ConnectorRepository repository,
            final ConnectorVersionChangeRepository changeRepository, final ConnectorAuditService audit) {
        this.repository = repository;
        this.changeRepository = changeRepository;
        this.audit = audit;
    }

    /** 升级登记（属主 + 已注册态；兼容立即生效，不兼容转待确认）。 */
    @Transactional
    public ConnectorVersionChange request(final String subjectNo, final String connectorNo,
            final String toVersion, final String compatibility) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorUpgradeService::notFound);
        if (!registration.subjectNo().equals(subjectNo)) {
            audit.denied(ConnectorAuditActions.DENIED, registration.applicationNo(), connectorNo, subjectNo,
                    ConnectorErrorCodes.NOT_FOUND_OR_DENIED.value(), "action=upgrade");
            throw notFound();
        }
        if (registration.status() != ConnectorStatus.REGISTERED) {
            throw stateNotAllowed();
        }
        final String targetVersion = ConnectorRegistrationService.normalizeVersion(toVersion);
        if (targetVersion.equals(registration.connectorVersion())) {
            throw new ConnectorBizException(ConnectorErrorCodes.VERSION_UNCHANGED,
                    ConnectorErrorCodes.VERSION_UNCHANGED_MESSAGE);
        }
        final Compatibility declared = Compatibility.parse(compatibility);
        if (declared == null) {
            throw paramInvalid();
        }
        if (changeRepository.findPendingByConnectorNo(connectorNo).isPresent()) {
            throw stateNotAllowed();
        }
        try {
            return declared == Compatibility.COMPATIBLE
                    ? applyCompatible(registration, subjectNo, targetVersion)
                    : requestIncompatible(registration, subjectNo, targetVersion, declared);
        } catch (final DuplicateKeyException e) {
            throw stateNotAllowed();
        }
    }

    /** 版本历史链（属主或运营方；时间倒序）。 */
    public List<ConnectorVersionChange> history(final String callerNo, final boolean operator,
            final String connectorNo, final PageQuery page) {
        requireVisible(callerNo, operator, connectorNo);
        return changeRepository.findByConnectorNo(connectorNo, page.offset(), page.pageSize());
    }

    public long countHistory(final String callerNo, final boolean operator, final String connectorNo) {
        requireVisible(callerNo, operator, connectorNo);
        return changeRepository.countByConnectorNo(connectorNo);
    }

    /** 待确认队列（运营方；提交时间升序，先到先处置）。 */
    public List<ConnectorVersionChange> pendingQueue(final PageQuery page) {
        return changeRepository.findPendingQueue(page.offset(), page.pageSize());
    }

    public long countPending() {
        return changeRepository.countPending();
    }

    /** 不兼容升级显式确认（运营方）：版本替换 + 变更行转 APPLIED，同一事务。 */
    @Transactional
    public ConnectorVersionChange confirm(final String operatorNo, final String connectorNo,
            final String upgradeNo) {
        final ConnectorVersionChange change = changeRepository.findByUpgradeNo(upgradeNo)
                .orElseThrow(ConnectorUpgradeService::notFound);
        if (!change.connectorNo().equals(connectorNo)) {
            throw notFound();
        }
        if (change.status() != VersionChangeStatus.PENDING_CONFIRM) {
            throw stateNotAllowed();
        }
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorUpgradeService::notFound);
        if (repository.updateVersion(connectorNo, change.fromVersion(), change.toVersion()) == 0) {
            throw stateNotAllowed();
        }
        final LocalDateTime now = LocalDateTime.now();
        if (changeRepository.apply(upgradeNo, operatorNo, now) == 0) {
            throw stateNotAllowed();
        }
        audit.success(ConnectorAuditActions.UPGRADE_CONFIRMED, registration.applicationNo(), connectorNo,
                operatorNo, "from=" + change.fromVersion() + ";to=" + change.toVersion()
                        + ";confirmedBy=" + operatorNo);
        return changeRepository.findByUpgradeNo(upgradeNo).orElseThrow(ConnectorUpgradeService::notFound);
    }

    private ConnectorVersionChange applyCompatible(final ConnectorRegistration registration,
            final String subjectNo, final String toVersion) {
        final String connectorNo = registration.connectorNo();
        final String fromVersion = registration.connectorVersion();
        if (repository.updateVersion(connectorNo, fromVersion, toVersion) == 0) {
            throw stateNotAllowed();
        }
        final String upgradeNo = changeRepository.nextUpgradeNo();
        changeRepository.insert(new ConnectorVersionChange(null, upgradeNo, connectorNo,
                registration.subjectNo(), fromVersion, toVersion, Compatibility.COMPATIBLE,
                VersionChangeStatus.APPLIED, subjectNo, null, LocalDateTime.now(), null));
        audit.success(ConnectorAuditActions.UPGRADE_APPLIED, registration.applicationNo(), connectorNo,
                subjectNo, "from=" + fromVersion + ";to=" + toVersion + ";compatibility=COMPATIBLE");
        return changeRepository.findByUpgradeNo(upgradeNo).orElseThrow(ConnectorUpgradeService::notFound);
    }

    private ConnectorVersionChange requestIncompatible(final ConnectorRegistration registration,
            final String subjectNo, final String toVersion, final Compatibility declared) {
        final String connectorNo = registration.connectorNo();
        final String fromVersion = registration.connectorVersion();
        final String upgradeNo = changeRepository.nextUpgradeNo();
        changeRepository.insert(new ConnectorVersionChange(null, upgradeNo, connectorNo,
                registration.subjectNo(), fromVersion, toVersion, declared,
                VersionChangeStatus.PENDING_CONFIRM, subjectNo, null, null, null));
        audit.success(ConnectorAuditActions.UPGRADE_REQUESTED, registration.applicationNo(), connectorNo,
                subjectNo, "from=" + fromVersion + ";to=" + toVersion + ";compatibility=INCOMPATIBLE");
        return changeRepository.findByUpgradeNo(upgradeNo).orElseThrow(ConnectorUpgradeService::notFound);
    }

    private void requireVisible(final String callerNo, final boolean operator, final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorUpgradeService::notFound);
        if (!operator && !registration.subjectNo().equals(callerNo)) {
            throw notFound();
        }
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }

    private static ConnectorBizException stateNotAllowed() {
        return new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
    }

    private static ConnectorBizException paramInvalid() {
        return new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
    }
}
