package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.delivery.application.SandboxQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据面零出口（hifi §7.1，行为 5 规则 1 / 行为 7 规则 3）：{@code /data/**} 一律 403 + 留痕，
 * 参与者与运营方同样不可直取；结果物只经 {@code /results} 提供元数据。
 */
@RestController
@RequestMapping("/api/v1/sandboxes")
public class SandboxDataDenialController {

    private final SandboxQueryService queryService;

    public SandboxDataDenialController(final SandboxQueryService queryService) {
        this.queryService = queryService;
    }

    /** 原始数据 / 产物内容直取：一律拒绝（1009C0012；非参与方 1009C0010）。 */
    @GetMapping(path = "/{sandboxNo}/data/**", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<Void> deny(@PathVariable final String sandboxNo) {
        queryService.denyDataAccess(sandboxNo, AuthContext.subject());
        return ApiResult.ok(null);
    }
}
