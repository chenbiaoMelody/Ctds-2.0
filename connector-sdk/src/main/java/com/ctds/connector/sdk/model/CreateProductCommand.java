package com.ctds.connector.sdk.model;

/** 产品发布命令（对齐平台目录域上架要素——数据集 / 名称 / 形态 / 计费模式）。 */
public record CreateProductCommand(Long datasetId, String productName, String intro,
        String productType, String pricingModel) {
}
