package com.ctds.contract.interfaces.dto;

import com.ctds.contract.domain.TemplateActionLog;
import java.time.LocalDateTime;

/**
 * 模板留痕视图（R3；hifi §2.2：四要素 + from→to + 拒绝理由码——剧本 S3-3 判定面；
 * 不含敏感原文与条款框架全文）。
 */
public record TemplateActionLogView(
        Long id,
        String templateNo,
        Integer versionNo,
        String action,
        String actorSubjectNo,
        String reasonCode,
        String fromValue,
        String toValue,
        LocalDateTime createdAt) {

    /** 领域 → 出站映射。 */
    public static TemplateActionLogView from(final TemplateActionLog log) {
        return new TemplateActionLogView(log.id(), log.templateNo(), log.versionNo(),
                log.action().name(), log.actorSubjectNo(), log.reasonCode(), log.fromValue(),
                log.toValue(), log.createdAt());
    }
}
