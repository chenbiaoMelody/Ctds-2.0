package com.ctds.example.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.example.application.DemoOrderService;
import com.ctds.example.application.DemoStockService;
import com.ctds.example.domain.DemoOrder;
import com.ctds.example.interfaces.dto.OrderSubmitRequest;
import com.ctds.example.interfaces.dto.OrderView;
import com.ctds.example.interfaces.dto.StockDeductRequest;
import com.ctds.example.interfaces.dto.StockDeductView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口层：幂等与分布式锁演示（WBS 2.4.7 B11）——
 * ①提交订单：同一订单号重复提交只处理一次、返回首次结果（幂等）；
 * ②扣减库存：并发扣同一商品串行执行、不超卖（分布式锁）。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoIdempotencyController {

    private final DemoOrderService orderService;
    private final DemoStockService stockService;

    public DemoIdempotencyController(final DemoOrderService orderService, final DemoStockService stockService) {
        this.orderService = orderService;
        this.stockService = stockService;
    }

    /** 提交订单（幂等演示）：重复提交同 orderNo → 返回首次创建结果（订单号/状态/时间与首次一致）。 */
    @PostMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<OrderView> submitOrder(@RequestBody final OrderSubmitRequest request) {
        final DemoOrder order = orderService.submit(request.orderNo());
        return ApiResult.ok(new OrderView(order.orderNo(), order.status(), order.submittedAt()));
    }

    /** 扣减库存（锁演示）：并发扣同一商品不超卖；库存不足 → 400"库存不足"。 */
    @PostMapping(path = "/stock/deduct", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<StockDeductView> deductStock(@RequestBody final StockDeductRequest request) {
        final int remaining = stockService.deduct(request.productId(), request.quantity());
        return ApiResult.ok(new StockDeductView(request.productId(), request.quantity(), remaining));
    }
}
