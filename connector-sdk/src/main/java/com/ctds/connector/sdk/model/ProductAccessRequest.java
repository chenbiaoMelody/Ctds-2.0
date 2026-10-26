package com.ctds.connector.sdk.model;

/**
 * API 型被交付资源访问请求（规格行为 4 / C-5.1 行为 2：V1.0 被交付资源经网关交付面以既有只读端点承载）。
 *
 * <p>凭据三件：合约编号必填；用途 / 域为策略要素，可空交平台判定（ADR-022 §2.5）。
 * GET 请求天然幂等，无需幂等键。</p>
 */
public record ProductAccessRequest(Long productId, String contractNo, String purpose, String territory) {
}
