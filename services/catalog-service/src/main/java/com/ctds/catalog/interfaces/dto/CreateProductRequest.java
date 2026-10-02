package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.application.ProductCommandService;
import java.math.BigDecimal;

/**
 * 产品封装请求体（WBS-3.3.5 hifi §1 W8；幂等经 X-Idempotency-Key 头由 ADR-007 切面自动承载）。
 * 六要素：datasetId/productName/intro/productType/pricingModel 必填，priceAmount 按档语义
 * （免费档不得携带——Q2-A），categoryCode 可选（缺省按资源申报类目继承）。
 */
public record CreateProductRequest(long datasetId, String productName, String intro, String productType,
        String pricingModel, BigDecimal priceAmount, String categoryCode) {

    /** 映射应用层命令（分层惯例：接口 DTO → 应用命令，沿 RegisterDatasetRequest.toCommand 先例）。 */
    public ProductCommandService.CreateProductCommand toCommand() {
        return new ProductCommandService.CreateProductCommand(datasetId, productName, intro,
                productType, pricingModel, priceAmount, categoryCode);
    }
}
