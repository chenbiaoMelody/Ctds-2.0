package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.TemplateQueryService;
import com.ctds.contract.domain.TemplateActionLog;
import com.ctds.contract.domain.TemplateStatus;
import com.ctds.contract.domain.TemplateType;
import com.ctds.contract.domain.TemplateVersion;
import com.ctds.contract.interfaces.dto.ManageTemplateView;
import com.ctds.contract.interfaces.dto.PageViews;
import com.ctds.contract.interfaces.dto.TemplateActionLogView;
import com.ctds.contract.interfaces.dto.TemplateVersionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板运营读面端点（WBS-3.4.2 hifi §2.2 R1~R3；Q8-A：版本历史与留痕查询归运营读面）。
 * 注解权限点 = contract.template.manage（仅 admin 档持有——yml 权限映射；读面拒绝无规格
 * 留痕义务，注解挡沿 catalog governance 点先例）。
 */
@RestController
@RequestMapping("/api/v1/contract-templates/manage")
public class ContractTemplateManageController {

    private final TemplateQueryService queryService;
    private final ObjectMapper objectMapper;

    public ContractTemplateManageController(final TemplateQueryService queryService,
            final ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    /** R1 全量模板分页（含停用；status/type 可选过滤——运营维护视图，支撑重新启用）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.manage")
    public ApiResult<PageResult<ManageTemplateView>> list(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String type,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<com.ctds.contract.domain.ContractTemplate> result = queryService.manageList(
                parseStatus(status), parseType(type), pageNum, pageSize);
        return ApiResult.ok(PageViews.page(result, ManageTemplateView::from));
    }

    /** R2 版本历史（旧版本保留可查——行为 1 规则 3；模板不存在 1008C0006 直述）。 */
    @GetMapping(path = "/{templateNo}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.manage")
    public ApiResult<List<TemplateVersionView>> versions(@PathVariable final String templateNo) {
        final List<TemplateVersion> versions = queryService.versions(templateNo);
        return ApiResult.ok(versions.stream().map(v -> TemplateVersionView.from(v, framework(v))).toList());
    }

    /** R3 留痕分页（四要素 + from→to + 拒绝理由码；templateNo 可选过滤——剧本 S3-3）。 */
    @GetMapping(path = "/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.manage")
    public ApiResult<PageResult<TemplateActionLogView>> actionLogs(
            @RequestParam(required = false) final String templateNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<TemplateActionLog> result = queryService.actionLogs(templateNo, pageNum, pageSize);
        return ApiResult.ok(PageViews.page(result, TemplateActionLogView::from));
    }

    /** 状态过滤参数解析（非法值 400 通用——沿 catalog 检索参数口径）。 */
    private TemplateStatus parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TemplateStatus.valueOf(status);
        } catch (final IllegalArgumentException e) {
            throw new com.ctds.contract.domain.ContractBizException(
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID,
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }

    /** 类型过滤参数解析（同上）。 */
    private TemplateType parseType(final String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return TemplateType.valueOf(type);
        } catch (final IllegalArgumentException e) {
            throw new com.ctds.contract.domain.ContractBizException(
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID,
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }

    /** 框架字符串 → JsonNode（解析失败返回 null 节点——存储层已校验合法）。 */
    private JsonNode framework(final TemplateVersion version) {
        try {
            return objectMapper.readTree(version.clauseFrameworkJson());
        } catch (final com.fasterxml.jackson.core.JacksonException e) {
            return objectMapper.nullNode();
        }
    }
}
