package com.ctds.contract.application;

import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.domain.TemplateVersion;

/**
 * 模板与当前版本的组合读取（浏览详情 R5 承载体：模板元数据 + 当前版本条款框架全文）。
 */
public record TemplateWithVersion(ContractTemplate template, TemplateVersion version) {
}
