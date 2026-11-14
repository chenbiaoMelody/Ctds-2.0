package com.ctds.evidence.application;

import java.util.List;

/**
 * WBS-3.9.3 读面视图（B9-1 六字段 + 配置校验结论 + 口径声明，hifi §2）：
 * 部署域"我是谁、什么版本、开着还是停了"的唯一事实出口。
 *
 * @param domainId          部署域标识
 * @param domainName        部署域名称
 * @param platformVersion   平台版本（升级 = 递增 + 滚动更新）
 * @param lifecycleStatus   生命周期状态（ACTIVE / RETIRED）
 * @param schemaList        库 schema 清单（声明值）
 * @param serviceList       服务清单（声明值）
 * @param configConsistency 配置校验结论：CONSISTENT / NOT_APPLICABLE（未配清单，诚实标注）
 * @param scopeNote         口径声明（V1.0 = 单实例单部署域，不做多实例自动开通）
 */
public record DomainView(
        String domainId,
        String domainName,
        String platformVersion,
        String lifecycleStatus,
        List<String> schemaList,
        List<String> serviceList,
        String configConsistency,
        String scopeNote) {

    /** 配置校验结论值域（B9-1；INCONSISTENT 以 503 + 1013S0002 出站，不出现在成功响应）。 */
    public static final String CONSISTENCY_CONSISTENT = "CONSISTENT";
    public static final String CONSISTENCY_NOT_APPLICABLE = "NOT_APPLICABLE";

    public static final String SCOPE_NOTE =
            "V1.0 = 单实例单部署域，生命周期 = 配置版本与状态管理，不做多实例自动开通";
}
