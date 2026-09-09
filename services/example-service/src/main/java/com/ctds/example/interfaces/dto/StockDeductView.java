package com.ctds.example.interfaces.dto;

/**
 * 扣减库存视图（锁演示）：remaining 为扣减后剩余（锁保护下并发扣减不超卖）。
 */
public record StockDeductView(String productId, int quantity, int remaining) {
}
