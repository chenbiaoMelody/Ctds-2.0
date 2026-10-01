package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CatalogProductRow;
import java.time.LocalDateTime;

/**
 * 产品详情视图（WBS-3.3.4 hifi §1 R7 出参 = R6 字段 + status；在架产品恒"已上架"，
 * 字段保留为详情契约完整性；intro 为全文）。
 *
 * @param productId         产品 id
 * @param productName       产品名称
 * @param intro             产品简介（全文）
 * @param productType       产品形态（业务可读中文）
 * @param pricingModel      定价模型（业务可读中文）
 * @param categoryCode      所属类目码（可为 null）
 * @param categoryName      类目名（可为 null）
 * @param providerSubjectNo 提供方主体编号
 * @param listedAt          上架时间
 * @param status            当前状态（在架详情恒"已上架"）
 */
public record CatalogProductDetail(long productId, String productName, String intro, String productType,
        String pricingModel, String categoryCode, String categoryName, String providerSubjectNo,
        LocalDateTime listedAt, String status) {

    /** 行 → 视图映射（仅 LISTED 行会到达此处）。 */
    public static CatalogProductDetail from(final CatalogProductRow row) {
        return new CatalogProductDetail(row.productId(), row.productName(), row.intro(),
                CatalogProductView.displayType(row.productType()),
                CatalogProductView.displayPricing(row.pricingModel()), row.categoryCode(),
                row.categoryName(), row.providerSubjectNo(), row.listedAt(), row.status().getDisplayName());
    }
}
