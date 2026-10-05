package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 合约模板（一行 = 一个合约模板；WBS-3.4.2 hifi §4，逐列对应 contract_template 表）。
 * 维护权唯一 = 平台运营方（行为 1 规则 1）；templateNo 平台生成、全平台唯一、不可变；
 * currentVersion 指向版本表当前版本（修订出新版本时前移，旧版本行保留可查——规则 3）。
 */
public record ContractTemplate(
        Long id,
        String templateNo,
        String name,
        TemplateType type,
        int currentVersion,
        TemplateStatus status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
