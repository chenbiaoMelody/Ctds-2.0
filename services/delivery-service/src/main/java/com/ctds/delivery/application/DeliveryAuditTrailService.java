package com.ctds.delivery.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryActionLog;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.interfaces.DeliveryInternalAuditController.DeliveryAuditTrailView;
import com.ctds.delivery.interfaces.DeliveryInternalAuditController.DeliveryAuditTrailView.ActionLogView;
import com.ctds.delivery.interfaces.DeliveryInternalAuditController.DeliveryAuditTrailView.DeliveryView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 交付域 audit-trail 内部只读查询（WBS-3.8.4 hifi §1；Q1-A 跨域留痕锚；delivery 首个内部端点）：
 * deliveryNo → 交付记录 + 留痕清单白名单字段（operatorNo / requesterNo 原文原样回读，
 * evidence 聚合层统一摘要化出站）；锚点不存在 → 400 + 1000C0003"记录不存在"
 * （与 evidence 审计读面同码同文，防枚举）。
 */
@Service
public class DeliveryAuditTrailService {

    private final DeliveryRepository repository;

    public DeliveryAuditTrailService(final DeliveryRepository repository) {
        this.repository = repository;
    }

    /** 交付锚点：交付记录 + 留痕清单。 */
    public DeliveryAuditTrailView trail(final String deliveryNo) {
        final Delivery delivery = repository.findByDeliveryNo(deliveryNo)
                .orElseThrow(() -> notFound());
        final List<ActionLogView> logs = repository.listActionLogsByDeliveryNo(deliveryNo)
                .stream().map(DeliveryAuditTrailService::toLogView).toList();
        return new DeliveryAuditTrailView(
                new DeliveryView(delivery.deliveryNo(), delivery.contractNo(),
                        delivery.deliveryType().name(), delivery.result(), delivery.fileName(),
                        delivery.fileSize(), toIso(delivery.createdAt())),
                logs);
    }

    private static ActionLogView toLogView(final DeliveryActionLog log) {
        return new ActionLogView(log.id(), log.deliveryNo(), log.contractNo(), log.operatorNo(),
                log.action(), log.result(), log.reasonCode(), toIso(log.createdAt()));
    }

    private static BizException notFound() {
        return new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "记录不存在");
    }

    private static String toIso(final LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
