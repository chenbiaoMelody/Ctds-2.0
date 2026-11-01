package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.OrderQueryService;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.OrderErrorCodes;
import com.ctds.contract.domain.OrderStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单读面端点（WBS-3.7.3 hifi §1.2 R-OR1/R-OR2/R-OR3）。权限点复用 contract.read /
 * contract.governance（Q1-A 零新权限点）；查询参数前置校验（非法 status/productId/contractNo
 * → 1011C0003——hifi §1.2 注；分页非法沿 common 1000C0001——hifi §8 勘误 E6）；详情防枚举
 * 同码同文在应用服务单点（hifi §3.4）。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderQueryController {

    private final OrderQueryService queryService;

    public OrderQueryController(final OrderQueryService queryService) {
        this.queryService = queryService;
    }

    /** R-OR1 本方订单分页（需方或供方；B4-1 + B12-1 双角色读，myRole 标注）。 */
    @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<PageResult<OrderQueryService.OrderSummary>> mine(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Long productId,
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<OrderQueryService.OrderSummary> result = queryService.mine(
                AuthContext.subject(), parseStatus(status), validateProductId(productId),
                validateContractNo(contractNo), PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(result);
    }

    /** R-OR2 订单详情（仅需方/供方可读；非本方与不存在同码同文 1011C0002 防枚举——B4-4）。 */
    @GetMapping(path = "/{orderNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<OrderQueryService.OrderDetail> detail(
            @PathVariable final String orderNo) {
        return ApiResult.ok(queryService.detail(orderNo, AuthContext.subject()));
    }

    /** R-OR3 运营方全量检索（B4-2；contract.governance 仅 admin；界面归 3.7.7——Q8-A）。 */
    @GetMapping(path = "/governance", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.governance")
    public ApiResult<PageResult<OrderQueryService.OrderSummary>> governance(
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Long productId,
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String requesterNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<OrderQueryService.OrderSummary> result = queryService.governance(
                parseStatus(status), validateProductId(productId),
                validateContractNo(contractNo),
                requesterNo == null ? null : requesterNo.trim(),
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(result);
    }

    /** status 过滤（仅三态；非法 → 1011C0003——hifi §1.2 注）。 */
    private OrderStatus parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim());
        } catch (final IllegalArgumentException e) {
            throw paramInvalid();
        }
    }

    /** productId 过滤（正整数；非法 → 1011C0003）。 */
    private Long validateProductId(final Long productId) {
        if (productId != null && productId < 1) {
            throw paramInvalid();
        }
        return productId;
    }

    /** contractNo 过滤（trim 后 ≤32；非法 → 1011C0003——hifi §1.2 注）。 */
    private String validateContractNo(final String contractNo) {
        if (contractNo == null || contractNo.isBlank()) {
            return null;
        }
        final String trimmed = contractNo.trim();
        if (trimmed.length() > 32) {
            throw paramInvalid();
        }
        return trimmed;
    }

    private ContractBizException paramInvalid() {
        return new ContractBizException(OrderErrorCodes.ORDER_PARAM_INVALID,
                OrderErrorCodes.ORDER_PARAM_INVALID_MESSAGE);
    }
}
