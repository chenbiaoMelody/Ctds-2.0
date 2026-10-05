package com.ctds.contract.interfaces.dto;

import com.ctds.contract.application.TemplateWithVersion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/**
 * 浏览详情（R5；hifi §2.3：模板元数据 + 当前版本条款框架全文——槽位键/名称/必填性/填写说明；
 * 响应不含任何数据本体与敏感原文，字段集由测试锚 T5 显式锚定）。
 */
public record TemplateDetailView(
        String templateNo,
        String name,
        TemplateView.TemplateTypeView type,
        int currentVersion,
        JsonNode clauseFramework,
        LocalDateTime publishedAt) {

    /** 领域 → 出站映射（框架字符串解析为 JsonNode 嵌入响应）。 */
    public static TemplateDetailView from(final TemplateWithVersion withVersion,
            final JsonNode framework) {
        final var template = withVersion.template();
        return new TemplateDetailView(template.templateNo(), template.name(),
                new TemplateView.TemplateTypeView(template.type().name(), template.type().getDisplayName()),
                template.currentVersion(), framework, withVersion.version().publishedAt());
    }
}
