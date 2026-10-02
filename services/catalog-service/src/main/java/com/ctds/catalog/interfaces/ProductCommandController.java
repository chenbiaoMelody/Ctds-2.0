package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.ProductCommandService;
import com.ctds.catalog.domain.ProviderProductRow;
import com.ctds.catalog.interfaces.dto.CancellationRequest;
import com.ctds.catalog.interfaces.dto.CreateProductRequest;
import com.ctds.catalog.interfaces.dto.ForceDelistRequest;
import com.ctds.catalog.interfaces.dto.ProviderProductView;
import com.ctds.catalog.interfaces.dto.UpdateProductRequest;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品命令端点（WBS-3.3.5 hifi §1：W8 封装 / W9 变更 / W10 上架 / W11 下架 / W12 强制下架 /
 * W13 注销 + R11 提供方管理列表）。权限点 = catalog.product（提供方管理动作第一道功能门槛）与
 * catalog.governance（W12 治理兜底，admin）；属主判定在应用服务落定（数据行相关，非静态门可表达——
 * 沿 {@code DatasetController} 双轨先例）。W8 幂等 = ADR-007 业务键（提供方+来源资源+归一化产品名，切面 SpEL 派生——hifi §11 勘误）。
 */
@RestController
@RequestMapping("/api/v1")
public class ProductCommandController {

    private final ProductCommandService commandService;

    public ProductCommandController(final ProductCommandService commandService) {
        this.commandService = commandService;
    }

    /** W8 封装（行为 3 全部规则；仅资源登记主体本人——应用服务判定；同键重复提交幂等返回首次结果）。 */
    @PostMapping(path = "/data-products", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<ProviderProductView> create(@RequestBody final CreateProductRequest body) {
        return ApiResult.ok(ProviderProductView.from(commandService.create(body.toCommand())));
    }

    /** W9 变更（行为 4 规则 5：仅提供方本人；逐字段 from→to 留痕；名称不可变更）。 */
    @PutMapping(path = "/data-products/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<ProviderProductView> update(@PathVariable final long productId,
            @RequestBody final UpdateProductRequest body) {
        return ApiResult.ok(ProviderProductView.from(commandService.update(productId,
                body.toCommand())));
    }

    /** W10 上架（行为 4 规则 2：定价齐备 + 资源有效 + 空间未解散 + ADMITTED 现值复核）。 */
    @PostMapping(path = "/data-products/{productId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<ProviderProductView> publish(@PathVariable final long productId) {
        return ApiResult.ok(ProviderProductView.from(commandService.publish(productId)));
    }

    /** W11 下架（行为 4 规则 3：仅在架可下架；下架后目录不再呈现）。 */
    @PostMapping(path = "/data-products/{productId}/delist", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<ProviderProductView> delist(@PathVariable final long productId) {
        return ApiResult.ok(ProviderProductView.from(commandService.delist(productId)));
    }

    /** W12 强制下架（行为 4 规则 4 治理兜底：平台运营方 + 理由必填 + 留痕含理由与操作者）。 */
    @PostMapping(path = "/data-products/{productId}/force-delist",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.governance")
    public ApiResult<ProviderProductView> forceDelist(@PathVariable final long productId,
            @RequestBody final ForceDelistRequest body) {
        return ApiResult.ok(ProviderProductView.from(commandService.forceDelist(productId,
                body.forceReason())));
    }

    /** W13 注销（行为 4 规则 1：二次确认必填、不可逆、仅未上架/已下架可发起——在架须先下架）。 */
    @PostMapping(path = "/data-products/{productId}/cancellation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<ProviderProductView> cancel(@PathVariable final long productId,
            @RequestBody final CancellationRequest request) {
        return ApiResult.ok(ProviderProductView.from(commandService.cancel(productId,
                request.confirmCancellation())));
    }

    /** R11 提供方管理列表（本人全状态产品分页——管理视图，行为 7 规则 1 本人读取面）。 */
    @GetMapping(path = "/data-products/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.product")
    public ApiResult<PageResult<ProviderProductView>> mine(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, null);
        final PageResult<ProviderProductRow> result = commandService.mine(page);
        return ApiResult.ok(PageResult.of(result.list().stream().map(ProviderProductView::from)
                .toList(), result.total(), page));
    }
}
