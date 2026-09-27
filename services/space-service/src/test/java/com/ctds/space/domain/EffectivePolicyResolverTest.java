package com.ctds.space.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.space.domain.EffectivePolicyResolver.EffectivePolicyRow;
import com.ctds.space.domain.EffectivePolicyResolver.PolicyProvenance;
import com.ctds.space.domain.EffectivePolicyResolver.PolicySource;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 有效策略解析器纯单测（WBS-3.2.5 hifi §8 T16，规格行为 7 规则 1/3）：继承默认、取严方向、
 * 来源三态标注（含"空间覆盖未生效（取严）"的可解释态）、目录声明序稳定排序、归档值参与解析。
 */
class EffectivePolicyResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Test
    void keyWithoutSpaceEntryInheritsPlatformDefault() {
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("data.visibility", "SPACE_MEMBER", 1, true)), List.of());
        assertThat(rows).hasSize(1);
        final EffectivePolicyRow row = rows.get(0);
        assertThat(row.entryKey()).isEqualTo("data.visibility");
        assertThat(row.effectiveValue()).isEqualTo("SPACE_MEMBER");
        assertThat(row.source()).isEqualTo(PolicySource.PLATFORM);
        assertThat(row.provenance()).isEqualTo(PolicyProvenance.INHERITED);
        assertThat(row.spaceValue()).isNull();
        assertThat(row.spaceStatus()).isNull();
        assertThat(row.redline()).isTrue();
        assertThat(row.displayName()).isEqualTo("数据可见范围");
    }

    @Test
    void stricterSpaceOverrideWins() {
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("data.retention", "D90", 1, true)),
                List.of(spaceEntry("data.retention", "D30", 2, PolicyStatus.ACTIVE)));
        assertThat(rows.get(0).effectiveValue()).as("空间收紧生效").isEqualTo("D30");
        assertThat(rows.get(0).source()).isEqualTo(PolicySource.SPACE);
        assertThat(rows.get(0).provenance()).isEqualTo(PolicyProvenance.SPACE_EFFECTIVE);
    }

    @Test
    void looserSpaceOverrideIsSuppressedByTakeStricter() {
        // 规则 3 取严兜底：非红线放宽覆盖落库但不生效，来源标注如实呈现（hifi §3 三态之二）
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("member.data_export", "FORBIDDEN", 1, false)),
                List.of(spaceEntry("member.data_export", "ALLOWED", 2, PolicyStatus.ACTIVE)));
        final EffectivePolicyRow row = rows.get(0);
        assertThat(row.effectiveValue()).isEqualTo("FORBIDDEN");
        assertThat(row.source()).isEqualTo(PolicySource.PLATFORM);
        assertThat(row.provenance()).isEqualTo(PolicyProvenance.SPACE_NOT_EFFECTIVE_TAKE_STRICTER);
        assertThat(row.note()).contains("FORBIDDEN").contains("ALLOWED").as("note 说明依据与取了哪一侧");
    }

    @Test
    void equalValueSpaceConfigCountsAsSpaceEffective() {
        // 严格度单射下平级即同值：显式配置优先呈现空间级（hifi §3）
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("data.retention", "D90", 1, true)),
                List.of(spaceEntry("data.retention", "D90", 2, PolicyStatus.ACTIVE)));
        assertThat(rows.get(0).effectiveValue()).isEqualTo("D90");
        assertThat(rows.get(0).provenance()).isEqualTo(PolicyProvenance.SPACE_EFFECTIVE);
    }

    @Test
    void rowsFollowCatalogDeclarationOrderRegardlessOfInputOrder() {
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("member.data_export", "ALLOWED", 3, false),
                        platformEntry("data.visibility", "SPACE_MEMBER", 1, true),
                        platformEntry("data.retention", "D90", 2, true)),
                List.of());
        assertThat(rows).extracting(EffectivePolicyRow::entryKey)
                .containsExactly("data.visibility", "data.retention", "member.data_export");
    }

    @Test
    void keyWithoutPlatformEntryIsOmittedHonestDefault() {
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("data.visibility", "SPACE_MEMBER", 1, true)),
                List.of(spaceEntry("data.retention", "D30", 9, PolicyStatus.ACTIVE)));
        assertThat(rows).extracting(EffectivePolicyRow::entryKey).containsExactly("data.visibility");
    }

    @Test
    void archivedSpaceEntryStillParticipatesAsFinalValue() {
        // 解散归档不可变、保留可查（行为 7 规则 5）：归档值参与解析且随行透出状态
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("member.data_export", "ALLOWED", 1, false)),
                List.of(spaceEntry("member.data_export", "FORBIDDEN", 2, PolicyStatus.ARCHIVED)));
        assertThat(rows.get(0).effectiveValue()).isEqualTo("FORBIDDEN");
        assertThat(rows.get(0).spaceStatus()).isEqualTo(PolicyStatus.ARCHIVED);
        assertThat(rows.get(0).provenance()).isEqualTo(PolicyProvenance.SPACE_EFFECTIVE);
    }

    @Test
    void multipleSpaceRowsPreferActiveThenLatestArchived() {
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(
                List.of(platformEntry("data.retention", "D90", 1, true)),
                List.of(spaceEntry("data.retention", "D180", 2, PolicyStatus.ARCHIVED),
                        spaceEntry("data.retention", "D30", 3, PolicyStatus.ACTIVE)));
        assertThat(rows.get(0).spaceValue()).as("ACTIVE 行优先于归档历史行").isEqualTo("D30");
    }

    private static SpacePolicy platformEntry(final String key, final String value, final long id,
            final boolean redline) {
        return new SpacePolicy(id, PolicyScope.PLATFORM, null, null, key, value, redline,
                PolicyStatus.ACTIVE, NOW, NOW);
    }

    private static SpacePolicy spaceEntry(final String key, final String value, final long id,
            final PolicyStatus status) {
        return new SpacePolicy(id, PolicyScope.SPACE, 1L, 100L, key, value, false, status, NOW, NOW);
    }
}
