package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CatalogProductRow;
import java.time.LocalDateTime;

/**
 * 我的收藏条目视图（WBS-3.3.4 hifi §1 R9 出参 = 目录元数据 + productStatus（读时计算，
 * Q5-A：已上架/已下架/已注销——条目本身不删）+ favoritedAt（首次收藏时间））。
 */
public record CatalogFavoriteItemView(long productId, String productName, String intro, String productType,
        String pricingModel, String categoryCode, String categoryName, String providerSubjectNo,
        LocalDateTime listedAt, String productStatus, LocalDateTime favoritedAt) {

    /** 行 → 视图映射（productStatus = 产品当前状态，读时计算）。 */
    public static CatalogFavoriteItemView from(final CatalogProductRow row) {
        return new CatalogFavoriteItemView(row.productId(), row.productName(), row.intro(),
                CatalogProductView.displayType(row.productType()),
                CatalogProductView.displayPricing(row.pricingModel()), row.categoryCode(),
                row.categoryName(), row.providerSubjectNo(), row.listedAt(),
                row.status().getDisplayName(), row.interactedAt());
    }
}
