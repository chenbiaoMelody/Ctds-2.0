package com.ctds.example.interfaces.dto;

import java.time.Instant;

/**
 * 订单视图（幂等演示）：重复提交同一订单号返回与首次一致的视图（结果复用）。
 */
public record OrderView(String orderNo, String status, Instant submittedAt) {
}
