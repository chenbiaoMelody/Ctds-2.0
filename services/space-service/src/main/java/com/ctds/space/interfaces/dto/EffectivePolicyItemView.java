package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.EffectivePolicyResolver.EffectivePolicyRow;
import java.util.List;

/**
 * 有效策略视图行（WBS-3.2.5 hifi §1 端点 5 出参：一行 = 一个目录键的取严解析结果，
 * 来源三态标注——继承自平台 / 空间级生效 / 空间覆盖未生效（取严），规则 3"可解释"）。
 */
public record EffectivePolicyItemView(
        String entryKey,
        String displayName,
        String effectiveValue,
        String source,
        String provenance,
        String note,
        String platformValue,
        String spaceValue,
        String spaceStatus,
        boolean redline) {

    public static EffectivePolicyItemView from(final EffectivePolicyRow row) {
        return new EffectivePolicyItemView(row.entryKey(), row.displayName(), row.effectiveValue(),
                row.source().name(), row.provenance().name(), row.note(), row.platformValue(),
                row.spaceValue(), row.spaceStatus() == null ? null : row.spaceStatus().name(),
                row.redline());
    }

    public static List<EffectivePolicyItemView> fromList(final List<EffectivePolicyRow> rows) {
        return rows.stream().map(EffectivePolicyItemView::from).toList();
    }
}
