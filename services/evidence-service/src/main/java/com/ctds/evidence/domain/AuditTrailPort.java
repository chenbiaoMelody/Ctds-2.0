package com.ctds.evidence.domain;

import java.util.List;
import java.util.Optional;

/**
 * 跨域 audit-trail 只读端口（WBS-3.8.4 hifi §1 / §3；Q1-A）：evidence 聚合层按锚点回读
 * 三域留痕白名单字段。实现 = infrastructure 三个 JDK HttpClient 客户端（connect 2s / read 3s、
 * 失败不重试 → empty，调用方显式标 UNAVAILABLE 降级 + WARN，不伪装连续）。
 * 出站脱敏在 evidence 聚合层统一执行——本端口回读的是域内原文（DID / 主体编号），不出平台。
 */
public interface AuditTrailPort {

    /** 空间域：准入锚点（admissionId）或留痕锚点（actionLogId）二选一。 */
    Optional<SpaceTrail> spaceTrail(long spaceId, Long admissionId, Long actionLogId);

    /** 合约域：合约锚点（存证要素 + 统一留痕）。 */
    Optional<ContractTrail> contractTrail(String contractNo);

    /** 合约域：使用放行留痕锚点。 */
    Optional<UsageTrail> usageTrail(long usageLogId);

    /** 交付域：交付锚点（交付记录 + 留痕）。 */
    Optional<DeliveryTrail> deliveryTrail(String deliveryNo);

    /** 空间域回读形态（admission 可空 = POLICY-AL 锚点；逐字段对应 SpaceInternalAuditController 视图）。 */
    record SpaceTrail(AdmissionRow admission, List<SpaceActionRow> actionLogs) {

        public record AdmissionRow(long id, String subjectNo, String type, String status,
                String operator, String reason, String createdAt) {
        }

        public record SpaceActionRow(long id, String targetType, Long targetId, String action,
                String operator, String fromValue, String toValue, String result, String reason,
                String createdAt) {
        }
    }

    /** 合约域回读形态（逐字段对应 ContractInternalAuditController 视图）。 */
    record ContractTrail(AttestationRow attestation, List<ContractActionRow> actionLogs) {

        public record AttestationRow(String contractNo, String contentHash, String providerDid,
                String providerSignedAt, String requesterDid, String requesterSignedAt,
                String attestedAt) {
        }

        public record ContractActionRow(long id, String action, String actorSubjectNo,
                String reasonCode, String fromValue, String toValue, String createdAt) {
        }
    }

    /** 使用放行回读形态（requesterNo 原文，由聚合层摘要化）。 */
    record UsageTrail(UsageRow usageLog) {

        public record UsageRow(long id, String contractNo, String requesterNo, String actionType,
                String outcome, String reasonCode, Integer usedCount, String occurredAt) {
        }
    }

    /** 交付域回读形态（逐字段对应 DeliveryInternalAuditController 视图）。 */
    record DeliveryTrail(DeliveryRow delivery, List<DeliveryActionRow> actionLogs) {

        public record DeliveryRow(String deliveryNo, String contractNo, String deliveryType,
                String result, String fileName, Long fileSize, String createdAt) {
        }

        public record DeliveryActionRow(long id, String action, String operatorNo, String result,
                String reasonCode, String createdAt) {
        }
    }
}
