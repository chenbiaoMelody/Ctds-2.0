package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.EffectivePolicyResolver.EffectivePolicyRow;

/**
 * 覆盖提交回显视图（WBS-3.2.5 hifi §1 端点 4 出参：提交后该键生效结果与来源标注即时回显）。
 */
public record PolicyOverrideView(
        String entryKey,
        String effectiveValue,
        String source,
        String provenance,
        String note,
        String platformValue,
        String spaceValue,
        boolean redline) {

    public static PolicyOverrideView from(final EffectivePolicyRow row) {
        return new PolicyOverrideView(row.entryKey(), row.effectiveValue(), row.source().name(),
                row.provenance().name(), row.note(), row.platformValue(), row.spaceValue(),
                row.redline());
    }
}
