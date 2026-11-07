package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.space.application.SpaceAuditTrailService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间域内部 audit-trail 只读端点（WBS-3.8.4 hifi §1；Q1-A：evidence 为唯一审计检索面，
 * 本端点仅供 evidence-service 以 evidence-internal 服务身份按锚点回读留痕白名单字段）：
 * spaceId + admissionId | actionLogId 二选一。权限点 space.internal.read 已存在（仅新增
 * evidence-internal 角色映射行，业务角色不持有——防空间状态与成员关系枚举沿 InternalMembership 口径）。
 */
@RestController
@RequestMapping("/api/v1/data-spaces/internal")
public class SpaceInternalAuditController {

    private final SpaceAuditTrailService service;

    public SpaceInternalAuditController(final SpaceAuditTrailService service) {
        this.service = service;
    }

    /** 准入锚点（admissionId）或留痕锚点（actionLogId）二选一的只读留痕回读。 */
    @GetMapping(path = "/audit-trails")
    @RequirePermission("space.internal.read")
    public ApiResult<SpaceAuditTrailView> auditTrail(
            @RequestParam final Long spaceId,
            @RequestParam(required = false) final Long admissionId,
            @RequestParam(required = false) final Long actionLogId) {
        return ApiResult.ok(service.trail(spaceId, admissionId, actionLogId));
    }

    /**
     * 留痕白名单视图（hifi §1：admission 七字段 + actionLogs 九字段；时间 = ISO-8601 UTC 瞬时串；
     * subjectNo / operator 原文不出平台——由 evidence 聚合层摘要化后出站）。
     */
    public record SpaceAuditTrailView(AdmissionView admission, List<ActionLogView> actionLogs) {

        /** 准入单白名单（无 spaceId：锚点已含；无 memberId：不外泄成员关系细节）。 */
        public record AdmissionView(Long id, String subjectNo, String type, String status,
                String operator, String reason, String createdAt) {
        }

        /** 留痕行白名单（逐列对应 space_action_log，动作码与结果枚举名）。 */
        public record ActionLogView(Long id, String targetType, Long targetId, String action,
                String operator, String fromValue, String toValue, String result, String reason,
                String createdAt) {
        }
    }
}
