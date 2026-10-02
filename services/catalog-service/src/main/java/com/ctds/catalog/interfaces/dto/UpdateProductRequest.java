package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.application.ProductCommandService;
import java.math.BigDecimal;

/**
 * 产品变更请求体（WBS-3.3.5 hifi §1 W9；null = 不变更该项；名称不可变更——沿 dataset 先例）。
 * 可变字段集 = 简介/形态/定价模型/定价数值/类目。
 */
public record UpdateProductRequest(String intro, String productType, String pricingModel,
        BigDecimal priceAmount, String categoryCode) {

    /** 映射应用层命令（沿 UpdateDatasetRequest.toCommand 先例）。 */
    public ProductCommandService.ProductUpdateCommand toCommand() {
        return new ProductCommandService.ProductUpdateCommand(intro, productType, pricingModel,
                priceAmount, categoryCode);
    }
}
