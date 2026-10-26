package com.ctds.connector.sdk.model;

/**
 * 使用执行请求（行为 5）：被交付资源 productId + 合约编号必填；用途 / 域为策略要素，
 * 可空交平台判定（ADR-022 §2.5）。GET 请求天然幂等，无需幂等键。
 */
public record UsageRequest(Long productId, String contractNo, String purpose, String territory) {
}
