package com.ctds.common.modelgateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** B2/B3 配置结构（普通 JavaBean，零 Spring 注解；绑定由消费方 @ConfigurationProperties(prefix = PREFIX) 承接）。 */
public class ModelGatewayProperties {

    public static final String PREFIX = "ctds.ai.gateway";

    /** 网关总开关（V1.0 零消耗安全默认）。 */
    private boolean enabled = false;
    /** 默认供应商（ADR-003 §2.1 默认智谱 GLM）。 */
    private String defaultProvider = ProviderIds.GLM;
    /** token 预算上限；0/负 = 未设限（预留位）。 */
    private long tokenBudget = 0L;
    /** 供应商连接参数表（键 = 供应商编号）。 */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(final String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public long getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(final long tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(final Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    /** 供应商连接参数；apiKey 值只经配置中心/KMS/环境注入（红线），仓库仅占位符。 */
    public static class ProviderConfig {

        /** 供应商 OpenAI 兼容服务地址（部署期注入，仓库零真实值）。 */
        private String baseUrl;
        /** 密钥字段位：值只经配置中心/KMS/环境注入（红线），仓库仅占位符。 */
        private String apiKey;
        /** 供应商默认模型名（部署期配置，代码不硬编码型号）。 */
        private String model;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(final String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(final String model) {
            this.model = model;
        }
    }
}
