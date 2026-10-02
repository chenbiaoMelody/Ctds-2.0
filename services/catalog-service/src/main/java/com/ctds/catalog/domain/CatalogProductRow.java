package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 目录产品行（WBS-3.3.4 hifi §1 R6/R7/R9/R10 读面统一行载体，仓储 join category_node 后返回；
 * 接口层 DTO 按端点契约裁剪出站字段集——R6 响应不含 status 字段（结果恒已上架），
 * R7/R9/R10 才出站状态）。
 *
 * @param productId         产品 id（对外即 /data-products/{productId} 路径参数，沿 R2 先例）
 * @param productName       产品名称
 * @param intro             产品简介
 * @param productType       产品形态（DatasetType 枚举名，沿资源四类同款）
 * @param pricingModel      定价模型（PricingModel 枚举名）
 * @param status            当前状态（R6 检索恒 LISTED；R9/R10 读时计算 = 产品当前状态，Q5-A）
 * @param providerSubjectNo 提供方主体编号
 * @param categoryCode      所属类目码（字符串载体，可为 null）
 * @param categoryName      类目名（join category_node；类目码为空或类目被移除时为 null）
 * @param listedAt          上架时间（目录默认排序键；非在架行可能为 null）
 * @param interactedAt      交互时间（R9 = 收藏时间 / R10 = 订阅时间；R6/R7 恒 null）
 */
public record CatalogProductRow(long productId, String productName, String intro, String productType,
        String pricingModel, ProductStatus status, String providerSubjectNo, String categoryCode,
        String categoryName, LocalDateTime listedAt, LocalDateTime interactedAt) {
}
