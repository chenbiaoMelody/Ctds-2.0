package com.ctds.space.domain;

import java.time.LocalDateTime;

/**
 * 空间策略条目（载体级；WBS-3.2.2 hifi §1.5，逐列对应 space_policy 表）。
 *
 * <p>条目键命名与可配置项集合归 3.2.5 策略继承引擎定稿（规格行为 7 规则 6：禁止两处各自定义策略模型）；
 * 红线条目（redline=true，仅平台级）为"不得放宽"判定依据；历史版本走留痕不在本表存版本链；
 * scopeUniq 为存储派生值（唯一性生成列）不进模型。</p>
 */
public record SpacePolicy(
        Long id,
        PolicyScope scope,
        Long spaceId,
        Long platformEntryId,
        String entryKey,
        String entryValue,
        boolean redline,
        PolicyStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
