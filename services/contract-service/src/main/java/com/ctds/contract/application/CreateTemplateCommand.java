package com.ctds.contract.application;

import com.ctds.contract.domain.TemplateType;

/**
 * 新增模板命令（hifi §2.1 W1；operatorNo 由控制器取 AuthContext 填充——演示期身份头口径，
 * 不收请求体传入，沿 catalog 先例）。clauseFrameworkJson 为条款框架 JSON 原文载体，
 * 校验与哈希在应用服务/领域层单点完成。
 */
public record CreateTemplateCommand(
        String operatorNo,
        String name,
        TemplateType type,
        String clauseFrameworkJson) {
}
