package com.ctds.evidence.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.domain.AccessLogEntry;
import com.ctds.evidence.domain.AccessLogRepository;
import com.ctds.evidence.domain.AuditQueryCriteria;
import com.ctds.evidence.domain.AuditTrailPort;
import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository;
import com.ctds.evidence.domain.EvidenceBusinessNoRouter;
import com.ctds.evidence.domain.EvidenceBusinessNoRouter.AuditAnchor;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 全链时间线与日志调阅应用服务（WBS-3.8.4 hifi §1 E3 / §3 流程 2~3、6；Q1-A / Q2-A）：
 * 单据号命中 = 存证事件 + 存证留痕 + 跨域业务留痕聚合（统一脱敏出站）；其余条件 = 存证清单
 * 分页模式；成功调阅落 1 条调阅留痕（留痕失败 = 1012S0002，不 fail-open，hifi §4.1）；
 * 跨域不可达 = 显式 UNAVAILABLE 降级 + WARN（只含域与单号，不含 DID 与原文，hifi §4.4）。
 *
 * <p>摘要口径（P1 对齐）：DID → {@link EvidenceDigests#subjectDidDigest}（CTDS-DID-1 前缀，
 * 与 subject_did_digest 逐字对齐）；主体编号 → {@link Sm3Service#digestHex}（无前缀直摘，
 * hifi §2 统一摘要化口径）。</p>
 */
@Service
public class EvidenceTrailService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceTrailService.class);

    private static final String MODE_TRAIL = "TRAIL";
    private static final String MODE_LIST = "LIST";
    private static final String SOURCE_SPACE = "SPACE";
    private static final String SOURCE_CONTRACT = "CONTRACT";
    private static final String SOURCE_DELIVERY = "DELIVERY";
    private static final String SOURCE_UNKNOWN = "UNKNOWN";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    private static final String LIST_HINT =
            "按单据号展开全链时间线：GET /api/v1/evidence/audit/trails?businessNo=<业务单据号>";

    private final EvidenceAuditQueryRepository auditRepository;
    private final EvidenceRepository evidenceRepository;
    private final AuditTrailPort trailPort;
    private final AccessLogRepository accessLogRepository;
    private final Sm3Service sm3;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceTrailService(final EvidenceAuditQueryRepository auditRepository,
            final EvidenceRepository evidenceRepository, final AuditTrailPort trailPort,
            final AccessLogRepository accessLogRepository, final Sm3Service sm3,
            final ObjectMapper objectMapper, final Clock clock) {
        this.auditRepository = auditRepository;
        this.evidenceRepository = evidenceRepository;
        this.trailPort = trailPort;
        this.accessLogRepository = accessLogRepository;
        this.sm3 = sm3;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** E3 调阅：businessNo 命中 = 单笔全链时间线；其余条件 = 清单分页。无条件 → 1012C0002。 */
    public EvidenceAuditViews.TrailView trails(final String stageType, final String businessNo,
            final String subjectDigest, final String from, final String to,
            final Integer pageNum, final Integer pageSize) {
        final AuditQueryCriteria criteria = EvidenceAuditConditions.parse(
                stageType, businessNo, subjectDigest, from, to, true);
        return criteria.businessNo() != null
                ? singleTrail(criteria.businessNo())
                : listTrail(criteria, pageNum, pageSize);
    }

    // ==== 单笔全链时间线 ====

    private EvidenceAuditViews.TrailView singleTrail(final String businessNo) {
        final EvidenceRecord record = auditRepository.findLatestByBusinessNo(businessNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND,
                        EvidenceAuditAccessGuard.AUDIT_RECORD_NOT_FOUND_MESSAGE));
        final List<EvidenceAuditViews.TrailActionView> actions =
                evidenceRepository.findActions(record.evidenceNo()).stream()
                        .map(this::toActionView)
                        .toList();
        final List<EvidenceAuditViews.TrailNodeView> nodes = buildBusinessTrail(businessNo);
        final ObjectNode conditions = objectMapper.createObjectNode();
        conditions.put("businessNo", businessNo);
        insertAccessLog(AccessLogEntry.KIND_TRAIL_ACCESS, conditions.toString(),
                actions.size() + nodes.size());
        return new EvidenceAuditViews.TrailView(MODE_TRAIL, EvidenceRecordView.from(record),
                actions, nodes, allNodesAvailable(nodes), null, null, null, null, null, null);
    }

    private List<EvidenceAuditViews.TrailNodeView> buildBusinessTrail(final String businessNo) {
        final List<EvidenceAuditViews.TrailNodeView> nodes = new ArrayList<>();
        final var anchor = EvidenceBusinessNoRouter.route(businessNo);
        if (anchor.isEmpty()) {
            nodes.add(unavailableNode(SOURCE_UNKNOWN, businessNo,
                    "单据号前缀未识别，跨域留痕不可用"));
            return nodes;
        }
        final var resolved = anchor.get();
        if (resolved instanceof AuditAnchor.SpaceAdmission space) {
            trailPort.spaceTrail(space.spaceId(), space.admissionId(), null).ifPresentOrElse(
                    trail -> {
                        if (trail.admission() != null) {
                            nodes.add(spaceAdmissionNode(trail.admission()));
                        }
                        trail.actionLogs().forEach(row -> nodes.add(spaceActionNode(row)));
                    },
                    () -> nodes.add(unavailableNode(SOURCE_SPACE, businessNo, null)));
        } else if (resolved instanceof AuditAnchor.SpaceActionLog space) {
            trailPort.spaceTrail(space.spaceId(), null, space.actionLogId()).ifPresentOrElse(
                    trail -> trail.actionLogs().forEach(row -> nodes.add(spaceActionNode(row))),
                    () -> nodes.add(unavailableNode(SOURCE_SPACE, businessNo, null)));
        } else if (resolved instanceof AuditAnchor.Contract contract) {
            trailPort.contractTrail(contract.contractNo()).ifPresentOrElse(
                    trail -> {
                        if (trail.attestation() != null) {
                            nodes.add(contractAttestationNode(trail.attestation()));
                        }
                        trail.actionLogs().forEach(row -> nodes.add(contractActionNode(row)));
                    },
                    () -> nodes.add(unavailableNode(SOURCE_CONTRACT, businessNo, null)));
        } else if (resolved instanceof AuditAnchor.Usage usage) {
            trailPort.usageTrail(usage.usageLogId()).ifPresentOrElse(
                    trail -> nodes.add(usageNode(trail.usageLog())),
                    () -> nodes.add(unavailableNode(SOURCE_CONTRACT, businessNo, null)));
        } else if (resolved instanceof AuditAnchor.Delivery delivery) {
            trailPort.deliveryTrail(delivery.deliveryNo()).ifPresentOrElse(
                    trail -> {
                        nodes.add(deliveryNode(trail.delivery()));
                        trail.actionLogs().forEach(row -> nodes.add(deliveryActionNode(row)));
                    },
                    () -> nodes.add(unavailableNode(SOURCE_DELIVERY, businessNo, null)));
        }
        nodes.sort(Comparator.comparing(
                EvidenceAuditViews.TrailNodeView::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return nodes;
    }

    // ==== 条件清单模式 ====

    private EvidenceAuditViews.TrailView listTrail(final AuditQueryCriteria criteria,
            final Integer pageNum, final Integer pageSize) {
        final int pn = pageNum == null ? 1 : pageNum;
        final int ps = pageSize == null ? 10 : pageSize;
        if (pn < 1 || pn > EvidenceAuditLimits.MAX_PAGE_NUM) {
            throw EvidenceAuditConditions.pageNumOutOfRange();
        }
        if (ps < 1 || ps > EvidenceAuditLimits.MAX_PAGE_SIZE) {
            throw EvidenceAuditConditions.pageSizeOutOfRange();
        }
        final PageQuery pageQuery = PageQuery.of(pn, ps, null);
        final EvidencePage page = auditRepository.pageByCriteria(criteria,
                (int) pageQuery.offset(), ps);
        final List<EvidenceRecordView> items = page.list().stream()
                .map(EvidenceRecordView::from).toList();
        final PageResult<EvidenceRecordView> pageResult =
                PageResult.of(items, page.total(), pageQuery);
        writeConditionsAccessLog(criteria, items.size());
        return new EvidenceAuditViews.TrailView(MODE_LIST, null, null, null, null,
                pageResult.total(), pageResult.pageNum(), pageResult.pageSize(),
                pageResult.totalPages(), pageResult.list(), LIST_HINT);
    }

    // ==== 跨域节点构建（脱敏出站）====

    private EvidenceAuditViews.TrailNodeView spaceAdmissionNode(
            final AuditTrailPort.SpaceTrail.AdmissionRow admission) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_SPACE, admission.createdAt(),
                digestSubject(admission.operator()), "ADMISSION:" + admission.type(),
                admission.status(), "空间准入 " + admission.type() + "，状态 " + admission.status(),
                STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView spaceActionNode(
            final AuditTrailPort.SpaceTrail.SpaceActionRow action) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_SPACE, action.createdAt(),
                digestSubject(action.operator()), action.action(), action.result(),
                action.action() + "，结果 " + action.result(), STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView contractAttestationNode(
            final AuditTrailPort.ContractTrail.AttestationRow attestation) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_CONTRACT, attestation.attestedAt(),
                digestDid(attestation.providerDid()), "CONTRACT_ATTESTED", "ATTESTED",
                "合约签署存证", STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView contractActionNode(
            final AuditTrailPort.ContractTrail.ContractActionRow action) {
        final String reason = action.reasonCode() == null || "NONE".equals(action.reasonCode())
                ? "" : "，原因码 " + action.reasonCode();
        return new EvidenceAuditViews.TrailNodeView(SOURCE_CONTRACT, action.createdAt(),
                digestSubject(action.actorSubjectNo()), action.action(),
                action.toValue() == null ? "LOGGED" : action.toValue(),
                action.action() + reason, STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView usageNode(
            final AuditTrailPort.UsageTrail.UsageRow usage) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_CONTRACT, usage.occurredAt(),
                digestSubject(usage.requesterNo()), usage.actionType(), usage.outcome(),
                "使用控制判定 " + usage.actionType() + "，结果 " + usage.outcome(), STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView deliveryNode(
            final AuditTrailPort.DeliveryTrail.DeliveryRow delivery) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_DELIVERY, delivery.createdAt(), null,
                "DELIVERY", delivery.result(),
                "交付 " + delivery.deliveryType() + "，结果 " + delivery.result(), STATUS_OK);
    }

    private EvidenceAuditViews.TrailNodeView deliveryActionNode(
            final AuditTrailPort.DeliveryTrail.DeliveryActionRow action) {
        return new EvidenceAuditViews.TrailNodeView(SOURCE_DELIVERY, action.createdAt(),
                digestSubject(action.operatorNo()), action.action(), action.result(),
                action.action() + "，结果 " + action.result(), STATUS_OK);
    }

    /** 降级节点（hifi §2：前缀不识别 → 仅存证侧 + UNAVAILABLE 说明；§4.4 WARN 只含域与单号）。 */
    private EvidenceAuditViews.TrailNodeView unavailableNode(final String source,
            final String businessNo, final String summary) {
        log.warn("audit trail segment unavailable: source={}, businessNo={}", source, businessNo);
        return new EvidenceAuditViews.TrailNodeView(source, null, null, null, null,
                summary == null ? "跨域留痕暂不可用（" + source + " 域未响应，未伪装连续）" : summary,
                STATUS_UNAVAILABLE);
    }

    private boolean allNodesAvailable(final List<EvidenceAuditViews.TrailNodeView> nodes) {
        return !nodes.isEmpty()
                && nodes.stream().allMatch(node -> STATUS_OK.equals(node.sourceStatus()));
    }

    // ==== 摘要与视图转换 ====

    /** DID 摘要（CTDS-DID-1 前缀，与 subject_did_digest 生成算法逐字对齐——P1 实测口径）。 */
    private String digestDid(final String did) {
        return did == null ? null : EvidenceDigests.subjectDidDigest(sm3, did);
    }

    /** 主体编号摘要（无前缀直摘——hifi §2 统一摘要化口径）。 */
    private String digestSubject(final String subjectNo) {
        return subjectNo == null ? null : sm3.digestHex(subjectNo);
    }

    private EvidenceAuditViews.TrailActionView toActionView(final EvidenceActionLog action) {
        return new EvidenceAuditViews.TrailActionView(action.action().name(),
                action.fromStatus() == null ? null : action.fromStatus().name(),
                action.toStatus() == null ? null : action.toStatus().name(),
                digestSubject(action.actorSubjectNo()), action.reasonCode(), action.detail(),
                EvidenceDigests.occurredAtIso(action.createdAt()));
    }

    // ==== 调阅留痕（B6-3；留痕失败 = 1012S0002，不 fail-open）====

    private void writeConditionsAccessLog(final AuditQueryCriteria criteria,
            final int resultCount) {
        final ObjectNode conditions = objectMapper.createObjectNode();
        if (criteria.stageType() != null) {
            conditions.put("stageType", criteria.stageType().name());
        }
        if (criteria.businessNo() != null) {
            conditions.put("businessNo", criteria.businessNo());
        }
        if (criteria.subjectDigest() != null) {
            conditions.put("subjectDigest", criteria.subjectDigest());
        }
        if (criteria.from() != null) {
            conditions.put("from", criteria.from().toString());
        }
        if (criteria.to() != null) {
            conditions.put("to", criteria.to().toString());
        }
        insertAccessLog(AccessLogEntry.KIND_TRAIL_ACCESS, conditions.toString(), resultCount);
    }

    private void insertAccessLog(final String kind, final String conditionsJson,
            final int resultCount) {
        try {
            accessLogRepository.insert(new AccessLogEntry(kind, AuthContext.subject(),
                    conditionsJson, resultCount, null, clock.instant()));
        } catch (final DataAccessException failure) {
            log.error("audit access log write failed: kind={}", kind, failure);
            throw new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                    EvidenceErrorCodes.STORAGE_FAILED_MESSAGE);
        }
    }
}
