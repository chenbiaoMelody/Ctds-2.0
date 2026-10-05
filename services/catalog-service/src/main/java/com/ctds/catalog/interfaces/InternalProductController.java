package com.ctds.catalog.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.catalog.application.ProductCatalogQueryService;
import com.ctds.catalog.domain.ProviderProductRow;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目录服务间内部只读端点（WBS-3.4.3 Q6-A 衔接；ADR-016 §6 衔接补记——contract 为第 6 消费方）：
 * 供 contract 发起门槛（产品在架 + 属主判定）与定价快照读取。最小暴露 6 字段（id/名称/
 * **原始状态值**〔含 DELISTED 等〕/属主主体编号/定价档/定价数值）；功能门槛 = **服务身份专用
 * 权限点** {@code catalog.internal.read}（仅授予 contract-internal，provider/admin 业务角色
 * 均不持有——沿 subject InternalAdmissionController 先例）；无资格门槛（服务身份调用）；
 * 不存在 → 1007C0011 同形（零新增 catalog 码）。
 */
@RestController
@RequestMapping("/api/v1/catalog/internal/data-products")
public class InternalProductController {

    private final ProductCatalogQueryService queryService;

    public InternalProductController(final ProductCatalogQueryService queryService) {
        this.queryService = queryService;
    }

    /** 产品事实（内部面最小暴露：无简介/类目/时间戳等非必要字段）。 */
    @GetMapping(path = "/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.internal.read")
    public ApiResult<InternalProductView> product(@PathVariable final long productId) {
        return ApiResult.ok(InternalProductView.from(queryService.internalProduct(productId)));
    }

    /** 产品事实视图（6 字段最小暴露；status 为原始状态值——LISTED/DRAFT/DELISTED/CANCELLED）。 */
    public record InternalProductView(long productId, String productName, String status,
            String providerSubjectNo, String pricingModel, BigDecimal priceAmount) {

        static InternalProductView from(final ProviderProductRow row) {
            return new InternalProductView(row.productId(), row.productName(),
                    row.status().name(), row.providerSubjectNo(), row.pricingModel(),
                    row.priceAmount());
        }
    }
}
