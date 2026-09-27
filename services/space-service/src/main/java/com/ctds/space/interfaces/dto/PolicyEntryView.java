package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.PolicyCatalog;
import com.ctds.space.domain.SpacePolicy;
import java.time.LocalDateTime;

/**
 * 策略条目视图（WBS-3.2.5 hifi §1 端点 1/2/3 出参；displayName 取自 PolicyCatalog 目录定义）。
 */
public record PolicyEntryView(
        Long id,
        String entryKey,
        String displayName,
        String entryValue,
        boolean redline,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PolicyEntryView from(final SpacePolicy entry) {
        return new PolicyEntryView(entry.id(), entry.entryKey(),
                PolicyCatalog.find(entry.entryKey())
                        .map(definition -> definition.displayName()).orElse(null),
                entry.entryValue(), entry.redline(), entry.status().name(),
                entry.createdAt(), entry.updatedAt());
    }
}
