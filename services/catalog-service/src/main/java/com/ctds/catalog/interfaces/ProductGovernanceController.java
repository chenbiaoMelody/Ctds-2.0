package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.ProductGovernanceService;
import com.ctds.catalog.interfaces.dto.DatasetView;
import com.ctds.catalog.interfaces.dto.ProviderProductView;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理例外读面端点（WBS-3.3.5 hifi §1 R12/R13；行为 7 规则 3"治理例外唯一且留痕"——C-3.2 剧本
 * S3-3 判定面）。权限点 = catalog.governance（admin，第一道功能门槛）；每次查看写 GOVERNANCE_VIEW
 * 留痕（DB-29 同款机制复用：通用码 + 实际登录主体 + 仅运营方触发——Q5-A）；读面拒绝不留痕
 * （DB-37 落定口径）。目录读面（R6/R7）零改动——治理例外不打开目录呈现面。
 */
@RestController
@RequestMapping("/api/v1")
public class ProductGovernanceController {

    private final ProductGovernanceService governanceService;

    public ProductGovernanceController(final ProductGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    /** R12 产品治理详情（任意状态含未上架/已下架/已注销；每次查看留痕）。 */
    @GetMapping(path = "/data-products/{productId}/governance",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.governance")
    public ApiResult<ProviderProductView> product(@PathVariable final long productId) {
        return ApiResult.ok(ProviderProductView.from(governanceService.product(productId)));
    }

    /** R13 资源治理详情（含申报字段与已注销对象；每次查看留痕）。 */
    @GetMapping(path = "/datasets/{datasetId}/governance", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.governance")
    public ApiResult<DatasetView> dataset(@PathVariable final long datasetId) {
        return ApiResult.ok(DatasetView.from(governanceService.dataset(datasetId)));
    }
}
