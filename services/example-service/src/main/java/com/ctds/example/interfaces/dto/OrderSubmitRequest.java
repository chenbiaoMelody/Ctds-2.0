package com.ctds.example.interfaces.dto;

/**
 * 提交订单请求（幂等演示）：orderNo 即幂等键。
 */
public record OrderSubmitRequest(String orderNo) {
}
