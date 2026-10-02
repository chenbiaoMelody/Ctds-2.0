package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.PricingModel;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProviderProductRow;
import java.time.LocalDateTime;

/**
 * 产品管理/治理视图（WBS-3.3.5 hifi §1 R11/R12 出站；字段集 = 管理面全量，
 * 含 status/priceAmount/datasetId——R6 检索白名单之外的本人/运营面字段）。
 * 出站表示口径沿产品面中文显示名轨（Q8-A：形态/定价档/状态 = "数据集/按次/已上架"）。
 */
public record ProviderProductView(long productId, String productName, String intro, String productType,
        String pricingModel, String priceAmount, String status, String providerSubjectNo,
        long datasetId, String categoryCode, String categoryName, LocalDateTime listedAt,
        LocalDateTime createdAt) {

    /** 由领域行映射视图（枚举 → 中文显示名，Q8-A 产品面轨；价格数值 → 业务可读文本或 null）。 */
    public static ProviderProductView from(final ProviderProductRow row) {
        return new ProviderProductView(row.productId(), row.productName(), row.intro(),
                DatasetType.valueOf(row.productType()).getDisplayName(),
                PricingModel.valueOf(row.pricingModel()).getDisplayName(),
                row.priceAmount() == null ? null : row.priceAmount().toPlainString(),
                ProductStatus.valueOf(row.status().name()).getDisplayName(),
                row.providerSubjectNo(), row.datasetId(), row.categoryCode(), row.categoryName(),
                row.listedAt(), row.createdAt());
    }
}
