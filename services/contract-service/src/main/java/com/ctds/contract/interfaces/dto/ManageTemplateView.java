package com.ctds.contract.interfaces.dto;

import com.ctds.contract.domain.ContractTemplate;
import java.time.LocalDateTime;

/**
 * 运营列表项（R1；hifi §2.2：全量含停用——status + 审计列，支撑重新启用与维护盘点）。
 */
public record ManageTemplateView(
        String templateNo,
        String name,
        TemplateView.TemplateTypeView type,
        int currentVersion,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 领域 → 出站映射。 */
    public static ManageTemplateView from(final ContractTemplate template) {
        return new ManageTemplateView(template.templateNo(), template.name(),
                new TemplateView.TemplateTypeView(template.type().name(), template.type().getDisplayName()),
                template.currentVersion(), template.status().name(), template.createdBy(),
                template.createdAt(), template.updatedAt());
    }
}
