package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.delivery.application.DeliveryAuditTrailService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交付域内部 audit-trail 只读端点（WBS-3.8.4 hifi §1 / §3；Q1-A：evidence 唯一审计检索面，
 * 本端点仅供 evidence-service 以 evidence-internal 身份回读留痕白名单字段）。
 * delivery 首个内部端点：新增权限点 delivery.internal.read（仅 evidence-internal 服务身份持有，
 * 业务角色不持有——防交付状态枚举，沿 contract.internal.read 消费方专用口径）。
 */
@RestController
public class DeliveryInternalAuditController {

    private final DeliveryAuditTrailService service;

    public DeliveryInternalAuditController(final DeliveryAuditTrailService service) {
        this.service = service;
    }

    /** 交付锚点：交付记录 + 留痕清单（DL 单据号审计回读）。 */
    @GetMapping(path = "/api/v1/deliveries/internal/{deliveryNo}/audit-trail")
    @RequirePermission("delivery.internal.read")
    public ApiResult<DeliveryAuditTrailView> auditTrail(@PathVariable final String deliveryNo) {
        return ApiResult.ok(service.trail(deliveryNo));
    }

    /**
     * 留痕白名单视图（hifi §1：delivery 七字段 + actionLogs 八字段；时间 = ISO-8601 UTC 瞬时串；
     * operatorNo / requesterNo 原文由 evidence 聚合层摘要化后出站）。
     */
    public record DeliveryAuditTrailView(DeliveryView delivery, List<ActionLogView> actionLogs) {

        /** 交付记录白名单（fileId / packageId / checksum / keyRef 不出审计面——密钥编号非审计要素）。 */
        public record DeliveryView(String deliveryNo, String contractNo, String deliveryType,
                String result, String fileName, Long fileSize, String createdAt) {
        }

        /** 留痕行白名单（逐列对应 delivery_action_log）。 */
        public record ActionLogView(Long id, String deliveryNo, String contractNo,
                String operatorNo, String action, String result, String reasonCode,
                String createdAt) {
        }
    }
}
