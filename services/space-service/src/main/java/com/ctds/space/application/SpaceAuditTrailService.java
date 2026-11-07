package com.ctds.space.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceAdmission;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.TargetType;
import com.ctds.space.interfaces.SpaceInternalAuditController.SpaceAuditTrailView;
import com.ctds.space.interfaces.SpaceInternalAuditController.SpaceAuditTrailView.ActionLogView;
import com.ctds.space.interfaces.SpaceInternalAuditController.SpaceAuditTrailView.AdmissionView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空间域 audit-trail 内部只读查询（WBS-3.8.4 hifi §1；Q1-A 跨域留痕锚）：
 * spaceId + admissionId | actionLogId 二选一，返回准入单与留痕行白名单字段
 * （subjectNo / operator 原文由 evidence 聚合层统一摘要化，本域原样回读）。
 * 锚点不存在或 spaceId 不匹配 → 统一 400 + 1000C0003"记录不存在"（与 evidence 审计读面同码同文，
 * ADR-016 §2.6 防枚举口径；不区分"不存在"与"参数错"）。
 */
@Service
public class SpaceAuditTrailService {

    private final SpaceRepository repository;

    public SpaceAuditTrailService(final SpaceRepository repository) {
        this.repository = repository;
    }

    /** 二选一锚点查询：admissionId → 准入单 + 其相关留痕；actionLogId → 单条留痕。 */
    public SpaceAuditTrailView trail(final Long spaceId, final Long admissionId,
            final Long actionLogId) {
        if (spaceId == null || (admissionId == null) == (actionLogId == null)) {
            throw notFound();
        }
        if (admissionId != null) {
            return admissionTrail(spaceId, admissionId);
        }
        return actionLogTrail(spaceId, actionLogId);
    }

    private SpaceAuditTrailView admissionTrail(final Long spaceId, final Long admissionId) {
        final SpaceAdmission admission = repository.findAdmissionById(admissionId)
                .filter(found -> found.spaceId().equals(spaceId))
                .orElseThrow(() -> notFound());
        final List<ActionLogView> logs = repository
                .listActionLogsByTarget(TargetType.ADMISSION, admissionId).stream()
                .map(SpaceAuditTrailService::toLogView)
                .toList();
        return new SpaceAuditTrailView(
                new AdmissionView(admission.id(), admission.subjectNo(), admission.type().name(),
                        admission.status().name(), admission.operator(), admission.reason(),
                        toIso(admission.createdAt())),
                logs);
    }

    private SpaceAuditTrailView actionLogTrail(final Long spaceId, final Long actionLogId) {
        final SpaceActionLog log = repository.findActionLogById(actionLogId)
                .filter(found -> spaceId.equals(found.spaceId()))
                .orElseThrow(() -> notFound());
        return new SpaceAuditTrailView(null, List.of(toLogView(log)));
    }

    private static ActionLogView toLogView(final SpaceActionLog log) {
        return new ActionLogView(log.id(), log.targetType().name(), log.targetId(), log.action(),
                log.operator(), log.fromValue(), log.toValue(), log.result().name(), log.reason(),
                toIso(log.createdAt()));
    }

    private static BizException notFound() {
        return new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "记录不存在");
    }

    /** UTC 墙钟 → ISO-8601 瞬时串（跨域出站统一口径，evidence 聚合层按瞬时解析）。 */
    private static String toIso(final LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
