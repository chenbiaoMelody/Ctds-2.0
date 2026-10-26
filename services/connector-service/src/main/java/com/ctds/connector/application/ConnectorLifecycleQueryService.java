package com.ctds.connector.application;

import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorBindingRepository;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorOccupancyRepository;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorRepository;
import com.ctds.connector.domain.ConnectorVersionChangeRepository;
import com.ctds.connector.domain.ConnectorVersionChange;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 生命周期聚合读（行为 10 验收 4"历史登记 / 版本 / 绑定 / 留痕全部保留可查"）：
 * 单一读面承载注销后的历史可追溯义务；属主或运营方可读，非属主同码同文（防枚举）。
 */
@Service
public class ConnectorLifecycleQueryService {

    /** 版本变更低频操作，单连接器历史上限防御性截断（非业务规则）。 */
    private static final int HISTORY_LIMIT = 200;

    private final ConnectorRepository repository;
    private final ConnectorBindingRepository bindingRepository;
    private final ConnectorOccupancyRepository occupancyRepository;
    private final ConnectorVersionChangeRepository changeRepository;
    private final ConnectorQueryService queryService;

    public ConnectorLifecycleQueryService(final ConnectorRepository repository,
            final ConnectorBindingRepository bindingRepository,
            final ConnectorOccupancyRepository occupancyRepository,
            final ConnectorVersionChangeRepository changeRepository,
            final ConnectorQueryService queryService) {
        this.repository = repository;
        this.bindingRepository = bindingRepository;
        this.occupancyRepository = occupancyRepository;
        this.changeRepository = changeRepository;
        this.queryService = queryService;
    }

    public LifecycleSnapshot snapshot(final String callerNo, final boolean operator,
            final String connectorNo) {
        final ConnectorRegistration registration = repository.findByConnectorNo(connectorNo)
                .orElseThrow(ConnectorLifecycleQueryService::notFound);
        if (!operator && !registration.subjectNo().equals(callerNo)) {
            throw notFound();
        }
        final List<ConnectorVersionChange> history =
                changeRepository.findByConnectorNo(connectorNo, 0, HISTORY_LIMIT);
        return new LifecycleSnapshot(registration,
                bindingRepository.findByConnectorNo(connectorNo).orElse(null),
                occupancyRepository.countInProgress(connectorNo),
                changeRepository.findPendingByConnectorNo(connectorNo).isPresent(),
                queryService.online(registration), history);
    }

    private static ConnectorBizException notFound() {
        return new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE);
    }
}
