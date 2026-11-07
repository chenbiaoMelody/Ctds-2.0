package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AuditTrailPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 跨域 audit-trail 聚合网关（WBS-3.8.4 hifi §1 / §3；AuditTrailPort 唯一实现）：
 * 按锚点域路由到三个 HTTP 适配器，聚合层（EvidenceTrailService）只依赖本端口。
 */
@Component
public class AuditTrailGateway implements AuditTrailPort {

    private final SpaceTrailClient spaceClient;
    private final ContractTrailClient contractClient;
    private final DeliveryTrailClient deliveryClient;

    public AuditTrailGateway(final SpaceTrailClient spaceClient,
            final ContractTrailClient contractClient,
            final DeliveryTrailClient deliveryClient) {
        this.spaceClient = spaceClient;
        this.contractClient = contractClient;
        this.deliveryClient = deliveryClient;
    }

    @Override
    public Optional<SpaceTrail> spaceTrail(final long spaceId, final Long admissionId,
            final Long actionLogId) {
        return spaceClient.fetch(spaceId, admissionId, actionLogId);
    }

    @Override
    public Optional<ContractTrail> contractTrail(final String contractNo) {
        return contractClient.fetchContract(contractNo);
    }

    @Override
    public Optional<UsageTrail> usageTrail(final long usageLogId) {
        return contractClient.fetchUsage(usageLogId);
    }

    @Override
    public Optional<DeliveryTrail> deliveryTrail(final String deliveryNo) {
        return deliveryClient.fetch(deliveryNo);
    }
}
