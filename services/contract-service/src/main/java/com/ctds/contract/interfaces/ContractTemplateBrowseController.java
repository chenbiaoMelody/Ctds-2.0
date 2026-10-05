package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.TemplateQueryService;
import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.interfaces.dto.PageViews;
import com.ctds.contract.interfaces.dto.TemplateDetailView;
import com.ctds.contract.interfaces.dto.TemplateView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板浏览读面端点（WBS-3.4.2 hifi §2.3 R4~R5；行为 1 规则 5 浏览边界）。
 * 注解权限点 = contract.template.read（已认证功能门槛）；**已入驻资格判定在应用服务**
 * （三态：未入驻/不存在 1008C0003 统一文案防枚举、UNAVAILABLE 1008S0001 不冒充）；
 * 浏览面仅呈现启用中模板，停用详情与不存在同码同文案（1008C0001，Q6-A 最严口径）。
 */
@RestController
@RequestMapping("/api/v1/contract-templates")
public class ContractTemplateBrowseController {

    private final TemplateQueryService queryService;
    private final ObjectMapper objectMapper;

    public ContractTemplateBrowseController(final TemplateQueryService queryService,
            final ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    /** R4 已入驻浏览列表（仅启用中；type 可选过滤，分页——剧本 C-4.1 S1-1 三类预置可见）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    public ApiResult<PageResult<TemplateView>> list(
            @RequestParam(required = false) final String type,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<ContractTemplate> result = queryService.browseList(AuthContext.subject(),
                pageNum, pageSize, parseType(type));
        return ApiResult.ok(PageViews.page(result, TemplateView::from));
    }

    /** R5 已入驻浏览详情（当前版本条款框架全文；停用/不存在同形 1008C0001——剧本 S1-2）。 */
    @GetMapping(path = "/{templateNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    public ApiResult<TemplateDetailView> detail(@PathVariable final String templateNo) {
        final var withVersion = queryService.browseDetail(AuthContext.subject(), templateNo);
        return ApiResult.ok(TemplateDetailView.from(withVersion, framework(withVersion)));
    }

    /** 类型过滤参数解析（非法值 400 通用——沿 catalog 检索参数口径）。 */
    private com.ctds.contract.domain.TemplateType parseType(final String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return com.ctds.contract.domain.TemplateType.valueOf(type);
        } catch (final IllegalArgumentException e) {
            throw new com.ctds.contract.domain.ContractBizException(
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID,
                    com.ctds.contract.domain.ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
        }
    }

    /** 框架字符串 → JsonNode（解析失败返回 null 节点——存储层已校验合法）。 */
    private JsonNode framework(final com.ctds.contract.application.TemplateWithVersion withVersion) {
        try {
            return objectMapper.readTree(withVersion.version().clauseFrameworkJson());
        } catch (final com.fasterxml.jackson.core.JacksonException e) {
            return objectMapper.nullNode();
        }
    }
}
