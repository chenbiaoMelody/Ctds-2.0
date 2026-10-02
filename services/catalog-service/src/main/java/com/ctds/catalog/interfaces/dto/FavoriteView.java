package com.ctds.catalog.interfaces.dto;

import java.time.LocalDateTime;

/**
 * 收藏动作视图（WBS-3.3.4 hifi §1 W4/W5 出参：productId + favoritedAt（首次时间——
 * 幂等重放返回的即首次时间））。
 */
public record FavoriteView(long productId, LocalDateTime favoritedAt) {
}
