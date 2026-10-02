package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.PricingModel;
import java.time.LocalDateTime;

/**
 * 目录产品视图（WBS-3.3.4 hifi §1 R6 出参；行为 5 规则 4 检索 ≠ 可访问——仅目录元数据白名单，
 * 不含资源本体、敏感原文与个人信息；响应不含 status 字段，结果恒已上架）。
 *
 * @param productId         产品 id
 * @param productName       产品名称
 * @param intro             产品简介
 * @param productType       产品形态（业务可读中文）
 * @param pricingModel      定价模型（业务可读中文）
 * @param categoryCode      所属类目码（可为 null）
 * @param categoryName      类目名（可为 null）
 * @param providerSubjectNo 提供方主体编号
 * @param listedAt          上架时间
 */
public record CatalogProductView(long productId, String productName, String intro, String productType,
        String pricingModel, String categoryCode, String categoryName, String providerSubjectNo,
        LocalDateTime listedAt) {

    /** 行 → 视图映射（形态/定价出站为业务可读中文）。 */
    public static CatalogProductView from(final CatalogProductRow row) {
        return new CatalogProductView(row.productId(), row.productName(), row.intro(),
                displayType(row.productType()), displayPricing(row.pricingModel()), row.categoryCode(),
                row.categoryName(), row.providerSubjectNo(), row.listedAt());
    }

    static String displayType(final String productType) {
        return DatasetType.valueOf(productType).getDisplayName();
    }

    static String displayPricing(final String pricingModel) {
        return PricingModel.valueOf(pricingModel).getDisplayName();
    }
}
