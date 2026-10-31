package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.OrderCommandService;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.OrderErrorCodes;
import com.ctds.contract.interfaces.dto.OrderRequests;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单写面端点（WBS-3.7.3 hifi §1.1 W-OR1）。权限点复用 {@code contract.deal}（订单 =
 * 合约业务外壳——Q1-A 零新权限点）；requestId / productId / templateNo / templateVersionNo
 * 前置校验（缺失或非法 → 1011C0003，零业务请求——hifi §3.2 ①）；条款值校验由合约发起
 * 门槛链单点承担（1008 段错误原样透传不包装）。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderCommandController {

    private final OrderCommandService commandService;

    public OrderCommandController(final OrderCommandService commandService) {
        this.commandService = commandService;
    }

    /** W-OR1 创建订单（201 = 订单 + 合约草案同事务落库；B3-1~B3-4）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("contract.deal")
    public ApiResult<OrderCommandService.OrderResult> create(
            @RequestBody final OrderRequests.Create request) {
        OrderCommandService.validateRequestId(request.requestId());
        if (request.productId() == null || request.productId() < 1) {
            throw paramInvalid();
        }
        if (request.templateNo() == null || request.templateNo().isBlank()) {
            throw paramInvalid();
        }
        if (request.templateVersionNo() == null || request.templateVersionNo() < 1) {
            throw paramInvalid();
        }
        final var cmd = new OrderCommandService.CreateOrderCommand(AuthContext.subject(),
                request.productId(), request.templateNo().trim(), request.templateVersionNo(),
                request.clauseValues(), request.requestId().trim());
        return ApiResult.ok(commandService.create(cmd));
    }

    private ContractBizException paramInvalid() {
        return new ContractBizException(OrderErrorCodes.ORDER_PARAM_INVALID,
                OrderErrorCodes.ORDER_PARAM_INVALID_MESSAGE);
    }
}
