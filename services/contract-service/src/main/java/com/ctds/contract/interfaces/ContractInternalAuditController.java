package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.ContractAuditTrailService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约域内部 audit-trail 只读端点两条路径（WBS-3.8.4 hifi §1；Q1-A：evidence 唯一审计检索面，
 * 本域仅供 evidence-service 以 evidence-internal 身份回读留痕白名单字段）。权限点
 * contract.internal.read 已存在（仅新增 evidence-internal 角色映射行，业务角色不持有，
 * 沿 gateway-internal / delivery-internal 先例）。
 */
@RestController
public class ContractInternalAuditController {

    private final ContractAuditTrailService service;

    public ContractInternalAuditController(final ContractAuditTrailService service) {
        this.service = service;
    }

    /** 合约锚点：存证要素 + 统一留痕清单（CO 单据号审计回读）。 */
    @GetMapping(path = "/api/v1/contracts/internal/{contractNo}/audit-trail")
    @RequirePermission("contract.internal.read")
    public ApiResult<ContractAuditTrailView> contractTrail(
            @PathVariable final String contractNo) {
        return ApiResult.ok(service.contractTrail(contractNo));
    }

    /** 使用放行锚点：单行留痕回读（USG 单据号审计回读）。 */
    @GetMapping(path = "/api/v1/contracts/internal/usage-logs/{usageLogId}/audit-trail")
    @RequirePermission("contract.internal.read")
    public ApiResult<UsageAuditTrailView> usageTrail(
            @PathVariable final long usageLogId) {
        return ApiResult.ok(service.usageTrail(usageLogId));
    }

    /**
     * 留痕白名单视图（hifi §1：attestation 五要素 + actionLogs 同构白名单；时间 = ISO-8601
     * UTC 瞬时串；DID / 主体编号原文由 evidence 聚合层摘要化后出站）。
     */
    public record ContractAuditTrailView(AttestationView attestation,
            List<ActionLogView> actionLogs) {

        /** 存证要素白名单（双方 DID 与签署时点；subjectNo 不出本端点——由聚合层按 DID 摘要）。 */
        public record AttestationView(String contractNo, String contentHash, String providerDid,
                String providerSignedAt, String requesterDid, String requesterSignedAt,
                String attestedAt) {
        }

        /** 统一留痕行白名单（逐列对应 contract_action_log；动作码 / 理由码为封闭码值）。 */
        public record ActionLogView(Long id, String contractNo, Integer versionNo, String action,
                String actorSubjectNo, String reasonCode, String fromValue, String toValue,
                String createdAt) {
        }
    }

    /** 使用放行留痕视图（requesterNo 原文由 evidence 聚合层摘要化，不出平台）。 */
    public record UsageAuditTrailView(UsageLogView usageLog) {

        /** 使用留痕行白名单（逐列对应 contract_usage_log，无 violations 明细——触发要素码不入审计面）。 */
        public record UsageLogView(long id, String contractNo, String requesterNo,
                String actionType, String outcome, String reasonCode, Integer usedCount,
                String occurredAt) {
        }
    }
}
