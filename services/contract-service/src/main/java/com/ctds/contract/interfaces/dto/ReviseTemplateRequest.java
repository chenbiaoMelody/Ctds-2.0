package com.ctds.contract.interfaces.dto;

import com.ctds.contract.application.ReviseTemplateCommand;
import com.ctds.contract.domain.ClauseFramework;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 修订模板请求体（hifi §2.1 W2）：frameworkHash 于映射时经 {@code ClauseFramework.stableHash}
 * 计算（幂等键成分——同框架内容重放返回首次新版本、不同内容各出新版本）。
 */
public record ReviseTemplateRequest(JsonNode clauseFramework) {

    /** 映射为应用层命令（operatorNo / templateNo 由控制器填充）。 */
    public ReviseTemplateCommand toCommand(final String operatorNo, final String templateNo) {
        final String frameworkJson = clauseFramework == null ? null : clauseFramework.toString();
        return new ReviseTemplateCommand(operatorNo, templateNo, frameworkJson,
                ClauseFramework.stableHash(frameworkJson));
    }
}
