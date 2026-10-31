package com.ctds.connector.application;

import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorBindingRepository;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorOccupancyRepository;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.ConnectorVersionChangeRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销（规格行为 10）：前置检查"无进行中交付作业"→ 状态 CANCELED + cancelled_at + 绑定解除（记录保留）
 * + 待确认升级作废，同一事务；注销后业务动作拒绝由既有读面与状态门槛承载。
 */
@Service
public class ConnectorCancellationService {

    private final ConnectorRepository repository;
    private final ConnectorBindingRepository bindingRepository;
    private final ConnectorOccupancyRepository occupancyRepository;
    private final ConnectorVersionChangeRepository changeRepository;
    private final ConnectorAuditService audit;

    public ConnectorCancellationService(final ConnectorRepository repository,
            final ConnectorBindingRepository bindingRepository,
            final ConnectorOccupancyRepository occupancyRepository,
            final ConnectorVersionChangeRepository changeRepository, final ConnectorAuditService audit) {
        this.repository = repository;
        this.bindingRepository = bindingRepository;
        this.occupancyRepository = occupancyRepository;
        this.changeRepository = changeRepository;
        this.audit = audit;
    }

    /** 注销已注册连接器（属主或运营方；有在途作业则拒绝）。 */
    @Transactional
    public ConnectorRegistration cancel(final String callerNo, final boolean operator,
            final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorCancellationService::notFound);
        if (!operator && !registration.subjectNo().equals(callerNo)) {
            audit.denied(ConnectorAuditActions.DENIED, registration.applicationNo(), connectorNo, callerNo,
                    ConnectorErrorCodes.NOT_FOUND_OR_DENIED.value(), "action=cancel");
            throw notFound();
        }
        if (registration.status() != ConnectorStatus.REGISTERED) {
            throw stateNotAllowed();
        }
        final long inProgress = occupancyRepository.countInProgress(connectorNo);
        if (inProgress > 0) {
            audit.denied(ConnectorAuditActions.CANCELLED, registration.applicationNo(), connectorNo, callerNo,
                    ConnectorErrorCodes.JOB_IN_PROGRESS.value(), "inProgress=" + inProgress);
            throw new ConnectorBizException(ConnectorErrorCodes.JOB_IN_PROGRESS,
                    ConnectorErrorCodes.JOB_IN_PROGRESS_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now();
        if (repository.cancel(connectorNo, now) == 0) {
            // WBS-3.6.8 W5：谓词 0 行 = 并发窗口内出现占用（E12）或状态已变更——复核给可读理由
            if (occupancyRepository.countInProgress(connectorNo) > 0) {
                throw new ConnectorBizException(ConnectorErrorCodes.JOB_IN_PROGRESS,
                        ConnectorErrorCodes.JOB_IN_PROGRESS_MESSAGE);
            }
            throw stateNotAllowed();
        }
        bindingRepository.release(connectorNo, now);
        changeRepository.voidPending(connectorNo);
        audit.success(ConnectorAuditActions.CANCELLED, registration.applicationNo(), connectorNo, callerNo,
                "fromStatus=REGISTERED;inProgress=0");
        return repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorCancellationService::notFound);
    }

    /** 撤回待核验申请（仅属主 + 仅待核验态；待核验档案无连接器编号，按申请单号寻址）。 */
    @Transactional
    public ConnectorRegistration withdraw(final String subjectNo, final String applicationNo) {
        final ConnectorRegistration registration = repository.findByApplicationNo(applicationNo)
                .orElseThrow(ConnectorCancellationService::notFound);
        if (!registration.subjectNo().equals(subjectNo)) {
            audit.denied(ConnectorAuditActions.DENIED, applicationNo, null, subjectNo,
                    ConnectorErrorCodes.NOT_FOUND_OR_DENIED.value(), "action=withdraw");
            throw notFound();
        }
        if (registration.status() != ConnectorStatus.PENDING_REVIEW) {
            throw stateNotAllowed();
        }
        if (repository.withdraw(applicationNo, LocalDateTime.now()) == 0) {
            throw stateNotAllowed();
        }
        audit.success(ConnectorAuditActions.CANCELLED, applicationNo, null, subjectNo,
                "fromStatus=PENDING_REVIEW;withdraw=true");
        return repository.findByApplicationNo(applicationNo)
                .orElseThrow(ConnectorCancellationService::notFound);
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }

    private static ConnectorBizException stateNotAllowed() {
        return new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
    }
}
