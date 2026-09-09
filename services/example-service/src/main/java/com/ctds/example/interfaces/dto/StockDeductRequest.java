package com.ctds.example.interfaces.dto;

/**
 * 扣减库存请求（锁演示）。
 */
public record StockDeductRequest(String productId, int quantity) {
}
