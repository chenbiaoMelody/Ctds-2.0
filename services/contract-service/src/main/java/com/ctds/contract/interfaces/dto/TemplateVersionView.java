package com.ctds.contract.interfaces.dto;

import com.ctds.contract.domain.TemplateVersion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/**
 * 模板版本视图（R2 版本历史；hifi §2.2：版本号/发布人/发布时间/框架全文——旧版本保留可查，
 * 行为 1 规则 3）。
 */
public record TemplateVersionView(
        int versionNo,
        String publishedBy,
        LocalDateTime publishedAt,
        JsonNode clauseFramework) {

    /** 领域 → 出站映射（框架字符串解析为 JsonNode 嵌入响应）。 */
    public static TemplateVersionView from(final TemplateVersion version, final JsonNode framework) {
        return new TemplateVersionView(version.versionNo(), version.publishedBy(),
                version.publishedAt(), framework);
    }
}
