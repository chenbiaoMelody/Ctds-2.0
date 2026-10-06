package com.ctds.contract.domain;

import java.math.BigDecimal;

/**
 * 目录域产品事实只读端口（WBS-3.4.3 hifi §1/§5 判定通道②；Q6-A catalog 内部只读端点，
 * 最小暴露 6 字段）。单一事实源在目录服务（ADR-016 §6 衔接契约第 6 消费方）：本端口只读
 * 不缓存；三态 = 可用 / NOT_FOUND（不存在同形）/ UNAVAILABLE（不冒充产品状态——防枚举口径，
 * hifi §3 1008C0010/1008S0003）。
 */
public interface CatalogProductPort {

    /** 产品事实三态拉取（W5 发起门槛与定价快照）。 */
    CatalogProductResult fetch(long productId);

    /** 产品事实（内部端点最小暴露 6 字段：id/名称/状态原值/属主主体编号/定价档/定价数值）。 */
    record CatalogProduct(long productId, String productName, String status,
            String providerSubjectNo, String pricingModel, BigDecimal priceAmount) {
    }

    /** 三态结果（FOUND 时 product 必非空；NOT_FOUND/UNAVAILABLE 时 product 为 null）。 */
    record CatalogProductResult(State state, CatalogProduct product) {

        public enum State { FOUND, NOT_FOUND, UNAVAILABLE }

        public static CatalogProductResult found(final CatalogProduct product) {
            return new CatalogProductResult(State.FOUND, product);
        }

        public static CatalogProductResult notFound() {
            return new CatalogProductResult(State.NOT_FOUND, null);
        }

        public static CatalogProductResult unavailable() {
            return new CatalogProductResult(State.UNAVAILABLE, null);
        }
    }
}
