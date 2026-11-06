package com.ctds.delivery.application;

import com.ctds.common.evidence.EvidenceEvent;
import com.ctds.common.evidence.EvidenceRecorder;
import com.ctds.delivery.domain.Delivery;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 交付环节存证埋点（WBS-3.8.3 hifi §2 E5）：新建交付成功 → {@code DELIVERY/DELIVERY_CREATED}
 * （单据号 {@code delivery_no} 逐字透传）。主体 DID 取合约内部摘要新增字段 {@code requesterDid}；
 * 缺失即跳过 + 告警（不伪造，规格 B1-4）。幂等重放 / 失败分支不调用（Q10）。
 */
@Component
public class DeliveryEvidencePublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEvidencePublisher.class);
    private static final String STAGE_DELIVERY = "DELIVERY";
    private static final String OP_DELIVERY_CREATED = "DELIVERY_CREATED";

    private final EvidenceRecorder evidenceRecorder;
    private final Clock clock;

    public DeliveryEvidencePublisher(final EvidenceRecorder evidenceRecorder, final Clock clock) {
        this.evidenceRecorder = evidenceRecorder;
        this.clock = clock;
    }

    /**
     * 交付创建成功（落库 + 审计之后）。
     *
     * @param delivery    落库后的交付记录（取 deliveryNo / requesterNo / createdAt）
     * @param requesterDid 合约内部摘要的需方 DID（可为 null）
     */
    public void deliveryCreated(final Delivery delivery, final String requesterDid) {
        if (requesterDid == null || requesterDid.isBlank()) {
            log.warn("交付存证跳过（需方 DID 缺失）: businessNo={}", delivery.deliveryNo());
            return;
        }
        final LocalDateTime occurredAt =
                delivery.createdAt() == null ? LocalDateTime.now(clock) : delivery.createdAt();
        evidenceRecorder.record(new EvidenceEvent(STAGE_DELIVERY, delivery.deliveryNo(),
                OP_DELIVERY_CREATED, requesterDid, occurredAt.atZone(clock.getZone()).toInstant(),
                delivery.requesterNo()));
    }
}
