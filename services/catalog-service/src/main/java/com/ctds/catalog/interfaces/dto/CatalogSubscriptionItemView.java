package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.CatalogProductRow;
import java.time.LocalDateTime;

/**
 * 我的订阅条目视图（WBS-3.3.4 hifi §1 R10 出参 = 目录元数据 + productStatus（读时计算，Q5-A）
 * + subscribedAt（首次订阅时间）；变更感知 = 订阅关系可查 + R8 变更留痕可查，Q6-A）。
 */
public record CatalogSubscriptionItemView(long productId, String productName, String intro, String productType,
        String pricingModel, String categoryCode, String categoryName, String providerSubjectNo,
        LocalDateTime listedAt, String productStatus, LocalDateTime subscribedAt) {

    /** 行 → 视图映射（productStatus = 产品当前状态，读时计算）。 */
    public static CatalogSubscriptionItemView from(final CatalogProductRow row) {
        return new CatalogSubscriptionItemView(row.productId(), row.productName(), row.intro(),
                CatalogProductView.displayType(row.productType()),
                CatalogProductView.displayPricing(row.pricingModel()), row.categoryCode(),
                row.categoryName(), row.providerSubjectNo(), row.listedAt(),
                row.status().getDisplayName(), row.interactedAt());
    }
}
