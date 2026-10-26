package com.ctds.connector.sdk.model;

/**
 * 使用执行结果（仅在三态之"放行"时返回——拒绝 / 不可达均以异常终止，无本地放行路径）：
 * resource = 平台经网关交付面返回的被交付资源（放行证据）。
 */
public record UsageResult(Long productId, ProductView resource) {
}
