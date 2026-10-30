package com.ctds.connector.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.config.ConnectorProperties;
import com.ctds.connector.domain.ConnectorActionLog;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 读面与心跳（行为 8 验收 2/3 + 规格 §7 Q8-A 健康度）：属主或运营方可读；非属主 / 不存在同码同文（防枚举）。
 * 列表**不查 DID 状态**（避免 N 次跨域调用，hifi §5 约定）；详情 / 绑定查验 / 内部面实时查 DID 域且不可用即 503。
 */
@Service
public class ConnectorQueryService {

    private final ConnectorRepository repository;
    private final ConnectorBindingRepository bindingRepository;
    private final ConnectorLogRepository logRepository;
    private final DidStatusPort didStatusPort;
    private final ConnectorProperties properties;
    private final ConnectorAuditService audit;

    public ConnectorQueryService(final ConnectorRepository repository,
            final ConnectorBindingRepository bindingRepository, final ConnectorLogRepository logRepository,
            final DidStatusPort didStatusPort, final ConnectorProperties properties,
            final ConnectorAuditService audit) {
        this.repository = repository;
        this.bindingRepository = bindingRepository;
        this.logRepository = logRepository;
        this.didStatusPort = didStatusPort;
        this.properties = properties;
        this.audit = audit;
    }

    /** 单申请详情（属主或运营方；否则同码同文）。 */
    public ConnectorRegistration registration(final String callerNo, final boolean operator,
            final String applicationNo) {
        final ConnectorRegistration registration = repository.findByApplicationNo(applicationNo)
                .orElseThrow(ConnectorQueryService::notFound);
        if (!operator && !registration.subjectNo().equals(callerNo)) {
            throw notFound();
        }
        return registration;
    }

    public List<ConnectorRegistration> myRegistrations(final String subjectNo, final PageQuery page) {
        return repository.findRegistrations(subjectNo, null, page.offset(), page.pageSize());
    }

    public long countMyRegistrations(final String subjectNo) {
        return repository.countRegistrations(subjectNo, null);
    }

    /** 待核验队列（运营方；固定 PENDING_REVIEW，申请时间升序——先到先审）。 */
    public List<ConnectorRegistration> reviewQueue(final PageQuery page) {
        return repository.findPendingQueue(page.offset(), page.pageSize());
    }

    public long countReviewQueue() {
        return repository.countRegistrations(null, ConnectorStatus.PENDING_REVIEW);
    }

    public List<ConnectorRegistration> myConnectors(final String subjectNo, final PageQuery page) {
        return repository.findRegistrations(subjectNo, ConnectorStatus.REGISTERED, page.offset(),
                page.pageSize());
    }

    public long countMyConnectors(final String subjectNo) {
        return repository.countRegistrations(subjectNo, ConnectorStatus.REGISTERED);
    }

    /** 运营方全平台连接器列表（WBS-3.6.7 Q3-A；status 空 = 三态全部）。 */
    public List<ConnectorRegistration> governanceConnectors(final ConnectorStatus status,
            final PageQuery page) {
        return repository.findRegistrations(null, status, page.offset(), page.pageSize());
    }

    public long countGovernanceConnectors(final ConnectorStatus status) {
        return repository.countRegistrations(null, status);
    }

    /** 连接器详情（含绑定与实时 DID 状态）。 */
    public ConnectorDetail detail(final String callerNo, final boolean operator, final String connectorNo) {
        final ConnectorRegistration registration = requireVisible(callerNo, operator, connectorNo);
        final Optional<ConnectorBinding> binding = bindingRepository.findActiveByConnectorNo(connectorNo);
        final CurrentDid currentDid = didStatusPort.current(registration.subjectNo());
        if (currentDid.isUnavailable()) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.DID_UNAVAILABLE,
                    ConnectorErrorCodes.DID_UNAVAILABLE_MESSAGE);
        }
        return new ConnectorDetail(registration, binding.orElse(null), currentDid.status(),
                currentDid.active(), online(registration));
    }

    /** 绑定查验（行为 8 验收 1~3）：实时 DID 状态；查验动作留痕（可追溯义务）。 */
    public ConnectorDetail binding(final String callerNo, final boolean operator, final String connectorNo) {
        final ConnectorRegistration registration = requireVisible(callerNo, operator, connectorNo);
        final ConnectorBinding binding = bindingRepository.findActiveByConnectorNo(connectorNo)
                .orElseThrow(ConnectorQueryService::notFound);
        final CurrentDid currentDid = didStatusPort.current(registration.subjectNo());
        if (currentDid.isUnavailable()) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.DID_UNAVAILABLE,
                    ConnectorErrorCodes.DID_UNAVAILABLE_MESSAGE);
        }
        audit.success(ConnectorAuditActions.BINDING_READ, registration.applicationNo(), connectorNo,
                callerNo, "didStatus=" + currentDid.status());
        return new ConnectorDetail(registration, binding, currentDid.status(), currentDid.active(),
                online(registration));
    }

    public List<ConnectorActionLog> actionLogs(final String callerNo, final boolean operator,
            final String connectorNo, final PageQuery page) {
        requireVisible(callerNo, operator, connectorNo);
        return logRepository.findByConnectorNo(connectorNo, page.offset(), page.pageSize());
    }

    public long countActionLogs(final String callerNo, final boolean operator, final String connectorNo) {
        requireVisible(callerNo, operator, connectorNo);
        return logRepository.countByConnectorNo(connectorNo);
    }

    /** 心跳上报（仅属主 + 仅已注册态）：刷新最近心跳，在线状态读时派生。 */
    public ConnectorDetail heartbeat(final String subjectNo, final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .filter(item -> item.subjectNo().equals(subjectNo))
                .orElseThrow(ConnectorQueryService::notFound);
        if (registration.status() != ConnectorStatus.REGISTERED) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        if (repository.touchHeartbeat(connectorNo, LocalDateTime.now()) == 0) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        audit.success(ConnectorAuditActions.HEARTBEAT, registration.applicationNo(), connectorNo,
                subjectNo, null);
        final ConnectorRegistration refreshed = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorQueryService::notFound);
        return new ConnectorDetail(refreshed,
                bindingRepository.findActiveByConnectorNo(connectorNo).orElse(null), null, false, true);
    }

    /** 内部面（供 3.6.4 / 3.6.7 / 3.6.8 与 SDK 校验）：含绑定与实时 DID 状态。 */
    public ConnectorDetail internal(final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .filter(item -> item.status() == ConnectorStatus.REGISTERED)
                .orElseThrow(ConnectorQueryService::notFound);
        final CurrentDid currentDid = didStatusPort.current(registration.subjectNo());
        if (currentDid.isUnavailable()) {
            throw new ConnectorUnavailableException(ConnectorErrorCodes.DID_UNAVAILABLE,
                    ConnectorErrorCodes.DID_UNAVAILABLE_MESSAGE);
        }
        return new ConnectorDetail(registration,
                bindingRepository.findActiveByConnectorNo(connectorNo).orElse(null),
                currentDid.status(), currentDid.active(), online(registration));
    }

    private ConnectorRegistration requireVisible(final String callerNo, final boolean operator,
            final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorQueryService::notFound);
        if (!operator && !registration.subjectNo().equals(callerNo)) {
            throw notFound();
        }
        return registration;
    }

    /** 在线派生（读时，无调度）：最近心跳未超阈值即在线；列表摘要与详情共用同一口径。 */
    public boolean online(final ConnectorRegistration registration) {
        final LocalDateTime last = registration.lastHeartbeatAt();
        if (last == null) {
            return false;
        }
        return !last.plus(properties.getHealth().getOfflineAfter()).isBefore(LocalDateTime.now());
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }
}
