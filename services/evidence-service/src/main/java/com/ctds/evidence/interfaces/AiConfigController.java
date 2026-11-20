package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.application.AiConfigService;
import com.ctds.evidence.application.AiConfigViews.AiConfigPreviewView;
import com.ctds.evidence.application.AiConfigViews.AiConfigSaveRequest;
import com.ctds.evidence.application.AiConfigViews.AiConfigView;
import com.ctds.evidence.domain.AiConfigChangeView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-3.10.4 Q2-A：AI 服务配置面 4 端点（hifi §1；**独立命名空间 /api/v1/ai-config**——
 * 不占用 /api/v1/platform 部署域读面封闭命名空间，沿各域独立路径惯例；前端新增对应 vite
 * 代理键）。读面显式挂 platform.ai.config.read（E1/E3/E4）、写面显式挂
 * platform.ai.config.write（E2 唯一写入口，POST 沿仓内写面惯例——接口层守卫禁
 * Put/Delete/Patch 改删动词）；操作者 = X-Ctds-Subject 头（经网关剥离重建，不可伪造直连；
 * 沿 PlatformMonitorController 口径）。出参零密钥字段（AiConfigViews 契约承载，
 * 规格 B1-3 / B4-1 红线）。
 */
@RestController
@RequestMapping("/api/v1/ai-config")
public class AiConfigController {

    private final AiConfigService aiConfigService;

    public AiConfigController(final AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    /** 读当前配置合并视图（E1；缺省态由 V6 seed 承载）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.ai.config.read")
    public ApiResult<AiConfigView> config() {
        return ApiResult.ok(aiConfigService.view());
    }

    /** 全量保存（E2 唯一写入口；校验拒绝 1014C0001 细分文案；留痕仅变化字段）。 */
    @PostMapping(path = "/save", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.ai.config.write")
    public ApiResult<AiConfigView> save(
            @RequestHeader(value = "X-Ctds-Subject", required = false) final String subject,
            @RequestBody final AiConfigSaveRequest request) {
        return ApiResult.ok(aiConfigService.save(request, subject));
    }

    /** 当前判定预览（E3；复用 3.10.1 判定语义，已用量恒 0，零外呼）。 */
    @GetMapping(path = "/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.ai.config.read")
    public ApiResult<AiConfigPreviewView> preview() {
        return ApiResult.ok(aiConfigService.preview());
    }

    /** 变更留痕分页（E4；只读五列）。 */
    @GetMapping(path = "/changes", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.ai.config.read")
    public ApiResult<PageResult<AiConfigChangeView>> changes(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(aiConfigService.changes(pageNum, pageSize));
    }
}
