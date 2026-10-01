package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.ProductInteractionService;
import com.ctds.catalog.interfaces.dto.FavoriteView;
import com.ctds.catalog.interfaces.dto.SubscriptionView;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目录交互写面端点（WBS-3.3.4 hifi §1；W4 收藏 / W5 取消收藏 / W6 订阅 / W7 退订）。
 * 权限点 = catalog.interact（第一道功能门槛）；W4/W6 的 ADMITTED 资格门槛由应用服务经
 * SubjectAdmissionPort 既有通道落定（W5/W7 为本人条目操作，条目产生于 ADMITTED 期，无该门槛）。
 * 全链链序与留痕口径见 {@code ProductInteractionService}（幂等重放先于状态门槛——hifi §1 定稿）。
 */
@RestController
@RequestMapping("/api/v1")
public class ProductInteractionController {

    private final ProductInteractionService interactionService;

    public ProductInteractionController(final ProductInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /** W4 收藏（行为 6 规则 1：仅已上架新发起；重复收藏幂等返回首次结果）。 */
    @PostMapping(path = "/data-products/{productId}/favorite", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.interact")
    public ApiResult<FavoriteView> favorite(@PathVariable final long productId) {
        return ApiResult.ok(new FavoriteView(productId, interactionService.favorite(productId)));
    }

    /** W5 取消收藏（行为 6 规则 1：仅本人条目；无条目 → 1007C0012 + DENIED 留痕）。 */
    @DeleteMapping(path = "/data-products/{productId}/favorite", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.interact")
    public ApiResult<FavoriteView> unfavorite(@PathVariable final long productId) {
        return ApiResult.ok(new FavoriteView(productId, interactionService.unfavorite(productId)));
    }

    /** W6 订阅（行为 6 规则 2：订阅关系登记；重复订阅幂等返回首次结果）。 */
    @PostMapping(path = "/data-products/{productId}/subscription", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.interact")
    public ApiResult<SubscriptionView> subscribe(@PathVariable final long productId) {
        return ApiResult.ok(new SubscriptionView(productId, interactionService.subscribe(productId)));
    }

    /** W7 退订（行为 6 规则 2/4：仅本人条目；无条目 → 1007C0012 + DENIED 留痕）。 */
    @DeleteMapping(path = "/data-products/{productId}/subscription", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("catalog.interact")
    public ApiResult<SubscriptionView> unsubscribe(@PathVariable final long productId) {
        return ApiResult.ok(new SubscriptionView(productId, interactionService.unsubscribe(productId)));
    }
}
