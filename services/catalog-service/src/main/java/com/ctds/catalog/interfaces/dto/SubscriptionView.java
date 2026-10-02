package com.ctds.catalog.interfaces.dto;

import java.time.LocalDateTime;

/**
 * 订阅动作视图（WBS-3.3.4 hifi §1 W6/W7 出参：productId + subscribedAt（首次时间——
 * 幂等重放返回的即首次时间））。
 */
public record SubscriptionView(long productId, LocalDateTime subscribedAt) {
}
