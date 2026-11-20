package com.ctds.evidence.domain;

import java.util.List;

/**
 * WBS-3.10.4 Q2-A：AI 网关配置面三表本地读写（领域接口沿 {@link RbacStore} 先例；
 * JDBC 实现见 infrastructure.JdbcAiConfigStore）。保存与留痕由调用方（AiConfigService）
 * 以同一事务包住（规格 B2-3）；api_key_ref 引用占位列不出本接口（页面与读 API 均不触达，红线）。
 */
public interface AiConfigStore {

    /** 总控状态行（单例行 id=1；缺省态由 V6 seed 承载）。 */
    GatewayState loadGatewayState();

    /** 封闭清单供应商状态行（不含密钥引用列——出库即不触达）。 */
    List<ProviderState> loadProviderStates();

    /** 保存总控（upsert 单例行）。 */
    void saveGatewayState(boolean enabled, String defaultProvider, long tokenBudget, String updatedBy);

    /** 保存供应商连接参数（upsert；仅 base_url/model 列，api_key_ref 永不经本方法触达）。 */
    void saveProviderState(String providerId, String baseUrl, String model);

    /** 逐字段变更留痕（值未变化的字段由调用方过滤后才会进入本方法）。 */
    void appendChanges(List<ChangeEntry> entries);

    /** 变更留痕分页（id 倒序，沿 JdbcRbacStore 口径）。 */
    List<AiConfigChangeView> changes(long offset, int limit);

    /** 变更留痕总数。 */
    long countChanges();

    /** 总控状态行。 */
    record GatewayState(boolean enabled, String defaultProvider, long tokenBudget) {
    }

    /** 供应商状态行。 */
    record ProviderState(String providerId, String baseUrl, String model) {
    }

    /** 变更留痕待写条目（changedBy/fieldPath/beforeValue/afterValue）。 */
    record ChangeEntry(String changedBy, String fieldPath, String beforeValue, String afterValue) {
    }
}
