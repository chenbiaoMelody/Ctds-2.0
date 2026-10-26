package com.ctds.connector.sdk.model;

import java.math.BigDecimal;

/**
 * 产品发布命令（对齐平台 CreateProductRequest：productType / pricingModel 取平台枚举名
 * （API / DATASET / REPORT / MODEL；FREE / PER_CALL / MONTHLY / REVENUE_SHARE）；付费档需 priceAmount）。
 */
public record CreateProductCommand(Long datasetId, String productName, String intro, String productType,
        String pricingModel, BigDecimal priceAmount, String categoryCode) {
}
