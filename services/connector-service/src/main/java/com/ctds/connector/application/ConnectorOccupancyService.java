package com.ctds.connector.application;

import com.ctds.connector.audit.ConnectorAuditActions;
import com.ctds.connector.audit.ConnectorAuditService;
import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorJobOccupancy;
import com.ctds.connector.domain.ConnectorOccupancyRepository;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorStatus;
import com.ctds.connector.domain.OccupancyStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在途交付作业占用（hifi §2 Q2-A）：使用方在取数前登记、完结后释放；注销前置检查唯一口径。
 * 登记与释放均幂等；接入方（交付链 / SDK）归 3.6.8，本卡只提供内部面与裁决口径。
 */
@Service
public class ConnectorOccupancyService {

    private final ConnectorRepository repository;
    private final ConnectorOccupancyRepository occupancyRepository;
    private final ConnectorAuditService audit;

    public ConnectorOccupancyService(final ConnectorRepository repository,
            final ConnectorOccupancyRepository occupancyRepository, final ConnectorAuditService audit) {
        this.repository = repository;
        this.occupancyRepository = occupancyRepository;
        this.audit = audit;
    }

    /** 登记在途作业（仅已注册态；同 jobRef 幂等）。WBS-3.6.8 W5：原子性以 SQL 谓词为准，
     * 服务层前置检查保留为快路径（可读拒绝理由），0 落行时以幂等重放复核区分竞态。 */
    @Transactional
    public ConnectorJobOccupancy occupy(final String operatorNo, final String connectorNo,
            final String jobRef) {
        final ConnectorRegistration registration = requireRegistered(connectorNo);
        final int rows = occupancyRepository.occupy(connectorNo, jobRef, LocalDateTime.now());
        if (rows == 0) {
            // 谓词 0 行：并发窗口内注册态变更，或同键幂等重放（值未变返回 0）——以在册 OCCUPY 行复核
            final Optional<ConnectorJobOccupancy> replay = occupancyRepository.find(connectorNo, jobRef);
            if (replay.isEmpty() || replay.get().status() != OccupancyStatus.OCCUPY) {
                throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                        ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
            }
        }
        audit.success(ConnectorAuditActions.OCCUPANCY_REGISTER, registration.applicationNo(), connectorNo,
                operatorNo, "jobRef=" + jobRef);
        return occupancyRepository.find(connectorNo, jobRef)
                .orElseThrow(ConnectorOccupancyService::notFound);
    }

    /** 释放作业（重复释放幂等：已释放直接返回既有记录，不重复留痕）。 */
    @Transactional
    public ConnectorJobOccupancy release(final String operatorNo, final String connectorNo,
            final String jobRef) {
        final ConnectorJobOccupancy occupancy = occupancyRepository.find(connectorNo, jobRef)
                .orElseThrow(ConnectorOccupancyService::notFound);
        if (occupancy.status() == OccupancyStatus.RELEASED) {
            return occupancy;
        }
        occupancyRepository.release(connectorNo, jobRef, LocalDateTime.now());
        audit.success(ConnectorAuditActions.OCCUPANCY_RELEASE, null, connectorNo, operatorNo,
                "jobRef=" + jobRef);
        return occupancyRepository.find(connectorNo, jobRef)
                .orElseThrow(ConnectorOccupancyService::notFound);
    }

    public List<ConnectorJobOccupancy> list(final String connectorNo) {
        requireConnector(connectorNo);
        return occupancyRepository.list(connectorNo);
    }

    private ConnectorRegistration requireRegistered(final String connectorNo) {
        final ConnectorRegistration registration = requireConnector(connectorNo);
        if (registration.status() != ConnectorStatus.REGISTERED) {
            throw new ConnectorBizException(ConnectorErrorCodes.STATE_NOT_ALLOWED,
                    ConnectorErrorCodes.STATE_NOT_ALLOWED_MESSAGE);
        }
        return registration;
    }

    private ConnectorRegistration requireConnector(final String connectorNo) {
        return repository.findByConnectorNo(connectorNo).orElseThrow(ConnectorOccupancyService::notFound);
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }
}
