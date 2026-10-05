package com.ctds.contract.interfaces.dto;

import com.ctds.contract.application.CreateTemplateCommand;
import com.ctds.contract.domain.TemplateType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 新增模板请求体（hifi §2.1 W1）：要素校验归应用服务（名称/类型 1008C0008、框架 1008C0004）；
 * 本 DTO 只做载荷映射，不含业务判定。operatorNo 由控制器取 AuthContext 填充
 * （演示期身份头口径，沿 catalog 先例）；type 非法值在绑定层拒绝并统一出站 1008C0008
 * （hifi V1.2 补正⑥——ContractExceptionHandler 承载）。
 */
public record CreateTemplateRequest(
        String name,
        TemplateType type,
        JsonNode clauseFramework) {

    /** 映射为应用层命令（框架 JsonNode 序列化为字符串载体）。 */
    public CreateTemplateCommand toCommand(final String operatorNo) {
        return new CreateTemplateCommand(operatorNo, name, type, clauseFramework == null ? null
                : clauseFramework.toString());
    }
}
