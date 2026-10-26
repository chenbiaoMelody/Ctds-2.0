package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 产品视图（对齐平台 ProviderProductView / CatalogProductDetail 共有核心字段）。
 * 经网关交付面 GET /api/v1/data-products/{productId} 返回的即本视图 = 被交付资源；
 * 形态 / 定价档 / 状态为业务可读中文（平台 Q8-A 产品面轨），未知字段忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(Long productId, String productName, String intro, String productType,
        String status) {
}
