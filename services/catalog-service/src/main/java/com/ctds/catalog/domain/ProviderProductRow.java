package com.ctds.catalog.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品管理/治理面行载体（WBS-3.3.5 hifi §1 R11/R12；区别于目录读面 {@link CatalogProductRow}：
 * 含 priceAmount 与 datasetId、status 恒出站——管理视图与治理例外需要全量字段）。
 * 仓储联 category_node 返回类目名；直造行（3.3.4 期测试/预置）priceAmount 可为 null。
 *
 * @param productId         产品 id（对外即 /data-products/{productId} 路径参数，沿 R2 先例）
 * @param productName       产品名称（原始输入）
 * @param intro             产品简介
 * @param productType       产品形态（DatasetType 枚举名）
 * @param pricingModel      定价模型（PricingModel 枚举名）
 * @param priceAmount       定价数值（免费档恒 null；语义随 pricingModel——Q2-A）
 * @param status            当前状态（管理视图含全部四态）
 * @param providerSubjectNo 提供方主体编号
 * @param datasetId         来源资源 id（行为 3 规则 4 一资源多产品的关联面）
 * @param categoryCode      所属类目码（可为 null）
 * @param categoryName      类目名（join category_node；类目码为空或类目缺失时 null）
 * @param listedAt          上架时间（非在架态 null——V3 注释锚定口径）
 * @param createdAt         创建时间
 */
public record ProviderProductRow(Long productId, String productName, String intro, String productType,
        String pricingModel, BigDecimal priceAmount, ProductStatus status, String providerSubjectNo,
        long datasetId, String categoryCode, String categoryName, LocalDateTime listedAt,
        LocalDateTime createdAt) {
}
