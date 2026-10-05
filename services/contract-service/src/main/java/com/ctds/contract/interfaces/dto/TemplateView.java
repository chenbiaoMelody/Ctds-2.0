package com.ctds.contract.interfaces.dto;

import com.ctds.contract.domain.ContractTemplate;

/**
 * 浏览列表项（R4；hifi §2.3 契约字段集：模板编号/名称/类型/当前版本号——不含状态与审计列，
 * 字段集由测试锚 T5 显式锚定）。
 */
public record TemplateView(String templateNo, String name, TemplateTypeView type, int currentVersion) {

    /** 类型出站形态（code + 中文名，业务可读）。 */
    public record TemplateTypeView(String code, String displayName) {
    }

    /** 领域 → 出站映射。 */
    public static TemplateView from(final ContractTemplate template) {
        return new TemplateView(template.templateNo(), template.name(),
                new TemplateTypeView(template.type().name(), template.type().getDisplayName()),
                template.currentVersion());
    }
}
