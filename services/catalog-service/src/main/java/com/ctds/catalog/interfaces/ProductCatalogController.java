package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.CategoryTreeService;
import com.ctds.catalog.application.ProductCatalogQueryService;
import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.ProductChangeLogRow;
import com.ctds.catalog.interfaces.dto.CatalogFavoriteItemView;
import com.ctds.catalog.interfaces.dto.CatalogProductDetail;
import com.ctds.catalog.interfaces.dto.CatalogProductView;
import com.ctds.catalog.interfaces.dto.CatalogSubscriptionItemView;
import com.ctds.catalog.interfaces.dto.CategoryNodeView;
import com.ctds.catalog.interfaces.dto.ProductChangeLogView;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一目录读面端点（WBS-3.3.4 hifi §1；编号续 R1~R4：R5 类目树 / R6 目录检索 / R7 产品详情 /
 * R8 产品变更留痕 / R9 我的收藏 / R10 我的订阅）。全部挂在既有 /api/v1 下，响应包装 ApiResult；
 * 权限点 = catalog.read（第一道功能门槛），检索/详情/留痕的 ADMITTED 资格门槛由应用服务经
 * SubjectAdmissionPort 既有通道落定（R5/R9/R10 无 ADMITTED 门槛——R5 沿 3.3.3 词表"平台公开字典"
 * 先例，R9/R10 恒仅本人条目）。
 */
@RestController
@RequestMapping("/api/v1")
public class ProductCatalogController {

    private final CategoryTreeService categoryTreeService;
    private final ProductCatalogQueryService queryService;

    public ProductCatalogController(final CategoryTreeService categoryTreeService,
            final ProductCatalogQueryService queryService) {
        this.categoryTreeService = categoryTreeService;
        this.queryService = queryService;
    }

    /** R5 类目树（全量 2 级，同级按 sort_order 升序；平台公开字典——认证 + 权限点，无 ADMITTED 门槛）。 */
    @GetMapping(path = "/catalog/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<List<CategoryNodeView>> categories() {
        return ApiResult.ok(CategoryNodeView.treeOf(categoryTreeService.allNodes()));
    }

    /** R6 目录检索（行为 5：恒仅已上架 + 分类过滤〔子树〕+ 关键词名称/简介 + 分页 + 上架时间倒序；
     * 响应不含 status 字段——结果恒已上架）。 */
    @GetMapping(path = "/data-products", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<PageResult<CatalogProductView>> search(
            @RequestParam(required = false) final String categoryCode,
            @RequestParam(required = false) final String keyword,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<CatalogProductRow> result =
                queryService.search(categoryCode, keyword, PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(mapProducts(result));
    }

    /** R7 产品详情（行为 5 规则 1 + 行为 7 规则 2：非在架/不存在同形拒绝——1007C0011 防枚举）。 */
    @GetMapping(path = "/data-products/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<CatalogProductDetail> detail(@PathVariable final long productId) {
        return ApiResult.ok(CatalogProductDetail.from(queryService.detail(productId)));
    }

    /** R8 产品变更留痕（行为 6 规则 2：限本人订阅者；非订阅者与产品行不存在同码同文案防枚举）。 */
    @GetMapping(path = "/data-products/{productId}/change-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<PageResult<ProductChangeLogView>> changeLogs(@PathVariable final long productId,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<ProductChangeLogRow> result =
                queryService.changeLogs(productId, PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(result.list().stream().map(ProductChangeLogView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages()));
    }

    /** R9 我的收藏（行为 6 规则 1/3：恒仅本人条目；productStatus 读时计算——条目保留不删）。 */
    @GetMapping(path = "/catalog/favorites", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<PageResult<CatalogFavoriteItemView>> favorites(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<CatalogProductRow> result =
                queryService.favorites(PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(result.list().stream()
                        .map(CatalogFavoriteItemView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages()));
    }

    /** R10 我的订阅（行为 6 规则 2/3：口径同 R9；变更感知 = 订阅关系可查 + R8 留痕可查）。 */
    @GetMapping(path = "/catalog/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.read")
    public ApiResult<PageResult<CatalogSubscriptionItemView>> subscriptions(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<CatalogProductRow> result =
                queryService.subscriptions(PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(result.list().stream()
                        .map(CatalogSubscriptionItemView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages()));
    }

    private static PageResult<CatalogProductView> mapProducts(final PageResult<CatalogProductRow> result) {
        return new PageResult<>(result.list().stream().map(CatalogProductView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
    }
}
