package com.ctds.evidence.application;

import java.util.List;

/**
 * WBS-3.10.4 视图与请求体（hifi §1；沿 MonitorViews 嵌套 record 先例）。
 * 出参零密钥字段（api_key_ref 不进任何视图，规格 B1-3 / B4-1 红线）。
 */
public final class AiConfigViews {

    private AiConfigViews() {
    }

    /** E1/E2 出参：合并视图（DB 有保存值用保存值，无保存值由 V6 seed 缺省态承载）。 */
    public record AiConfigView(boolean enabled, String defaultProvider, long tokenBudget,
            List<ProviderView> providers) {
    }

    /** 供应商行视图（providerName/isDefaultProvider 由常量目录组装，不落库；configured=地址或模型名至少一项非空）。 */
    public record ProviderView(String providerId, String providerName, boolean isDefaultProvider,
            String baseUrl, String model, boolean configured) {
    }

    /** E2 入参：全量提交（hifi §1.2；校验规则见 AiConfigService.validate）。 */
    public record AiConfigSaveRequest(Boolean enabled, String defaultProvider, Long tokenBudget,
            List<ProviderSaveItem> providers) {
    }

    /** 保存载荷供应商行（仅编号 + 连接参数；密钥相关字段零承载）。 */
    public record ProviderSaveItem(String providerId, String baseUrl, String model) {
    }

    /** E3 出参：判定预览（decision 三态 + 业务可读 reason）。 */
    public record AiConfigPreviewView(String decision, String reason) {
    }
}
