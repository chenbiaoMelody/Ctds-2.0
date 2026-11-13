package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.application.RbacPermissionService;
import com.ctds.evidence.application.RbacRoleView;
import com.ctds.evidence.domain.RbacChangeView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-3.9.2 hifi §2 契约表：W1/W2 授予收回（安全管理员侧 connector.review）、
 * R1 角色权限清单（系统管理员侧 connector.health.read）、R2 变更留痕（审计管理员侧
 * evidence.governance）。平台管理面端点全部显式挂权限点（B3-R4），零本表之外的端点。
 */
@RestController
@RequestMapping("/api/v1/rbac")
public class RbacGovernanceController {

    private final RbacPermissionService permissionService;

    public RbacGovernanceController(final RbacPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping(path = "/permissions/grant", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.review")
    public ApiResult<String> grant(@RequestBody final RbacGrantRequest request,
            @RequestHeader(value = "X-Ctds-Subject", required = false) final String changedBy) {
        return ApiResult.ok(permissionService.grant(request.role(), request.permission(),
                request.changeOrderNo(), changedBy));
    }

    @PostMapping(path = "/permissions/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.review")
    public ApiResult<String> revoke(@RequestBody final RbacGrantRequest request,
            @RequestHeader(value = "X-Ctds-Subject", required = false) final String changedBy) {
        return ApiResult.ok(permissionService.revoke(request.role(), request.permission(),
                request.changeOrderNo(), changedBy));
    }

    @GetMapping(path = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("connector.health.read")
    public ApiResult<List<RbacRoleView>> roles() {
        return ApiResult.ok(permissionService.roles());
    }

    @GetMapping(path = "/changes", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<PageResult<RbacChangeView>> changes(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(permissionService.changes(pageNum, pageSize));
    }
}
