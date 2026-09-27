package com.ctds.space.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 有效策略解析引擎（WBS-3.2.5 hifi §3，规格行为 7 规则 1/3 的领域纯函数，无 IO）：
 * 输入 = 平台级 ACTIVE 条目集 + 空间级条目集（ACTIVE/ARCHIVED 均参与——归档即终态值，保留可查），
 * 输出 = 逐键取严的有效策略行（按目录声明序稳定排序）。
 *
 * <p>解析 = 读时计算不落库（无物化表——平台值变更即时反映于视图，无同步一致性问题）。
 * 来源三态标注满足规则 3"判定结果可解释（依据哪一条、取了哪一侧）"。前置条件：输入条目值
 * 均在 {@link PolicyCatalog} 值域内（写路径已校验）。无平台级 ACTIVE 条目的目录键不出现在
 * 视图（诚实缺省——平台基线由 V3 种子与平台治理端点维持齐备）。</p>
 */
public final class EffectivePolicyResolver {

    /** 有效值来源（规则 3"取了哪一侧"）。 */
    public enum PolicySource {
        /** 平台级（继承或取严后平台更严）。 */
        PLATFORM("平台级"),
        /** 空间级（覆盖生效）。 */
        SPACE("空间级");

        private final String displayName;

        PolicySource(final String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** 来源三态（规则 1 继承 / 规则 3 取严的可解释标注）。 */
    public enum PolicyProvenance {
        /** 无空间覆盖——继承自平台。 */
        INHERITED("继承自平台"),
        /** 空间覆盖生效（更严，或与平台同值的显式配置）。 */
        SPACE_EFFECTIVE("空间级覆盖生效"),
        /** 空间覆盖存在但平台更严——取严兜底，空间覆盖未生效。 */
        SPACE_NOT_EFFECTIVE_TAKE_STRICTER("空间覆盖未生效（取严）");

        private final String displayName;

        PolicyProvenance(final String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** 有效策略行（一行 = 一个目录键的解析结果；spaceValue/spaceStatus 为空 = 无空间覆盖）。 */
    public record EffectivePolicyRow(
            String entryKey,
            String displayName,
            boolean redline,
            String platformValue,
            String spaceValue,
            PolicyStatus spaceStatus,
            String effectiveValue,
            PolicySource source,
            PolicyProvenance provenance,
            String note) {
    }

    private EffectivePolicyResolver() {
    }

    /**
     * 逐键取严解析：每键取平台级与空间级更严一侧（严格度大者；平级 = 同值显式配置，取空间级）。
     *
     * @param platformEntries 平台级条目（须 scope=PLATFORM、status=ACTIVE）
     * @param spaceEntries    空间级条目（ACTIVE/ARCHIVED 均参与；同键多行时 ACTIVE 优先、其余取最新）
     */
    public static List<EffectivePolicyRow> resolve(final List<SpacePolicy> platformEntries,
            final List<SpacePolicy> spaceEntries) {
        final List<EffectivePolicyRow> rows = new ArrayList<>();
        for (final String key : PolicyCatalog.keys()) {
            final Optional<SpacePolicy> platform = platformEntries.stream()
                    .filter(entry -> entry.scope() == PolicyScope.PLATFORM
                            && entry.status() == PolicyStatus.ACTIVE
                            && key.equals(entry.entryKey()))
                    .findFirst();
            if (platform.isEmpty()) {
                // 无平台级 ACTIVE 条目的键不出现在视图（诚实缺省，hifi §3）
                continue;
            }
            final Optional<SpacePolicy> space = spaceEntries.stream()
                    .filter(entry -> key.equals(entry.entryKey()))
                    .reduce((first, second) -> pickLatest(first, second));
            rows.add(resolveRow(PolicyCatalog.find(key).orElseThrow(), platform.get(), space));
        }
        return rows;
    }

    private static SpacePolicy pickLatest(final SpacePolicy first, final SpacePolicy second) {
        if (first.status() == PolicyStatus.ACTIVE) {
            return first;
        }
        if (second.status() == PolicyStatus.ACTIVE) {
            return second;
        }
        return first.id() >= second.id() ? first : second;
    }

    private static EffectivePolicyRow resolveRow(final PolicyCatalog.EntryDefinition definition,
            final SpacePolicy platform, final Optional<SpacePolicy> space) {
        final String key = definition.key();
        if (space.isEmpty()) {
            return new EffectivePolicyRow(key, definition.displayName(), platform.redline(),
                    platform.entryValue(), null, null, platform.entryValue(),
                    PolicySource.PLATFORM, PolicyProvenance.INHERITED,
                    "未在空间级显式配置，按平台级默认执行");
        }
        final SpacePolicy spaceRow = space.get();
        final int platformStrictness = PolicyCatalog.strictnessOf(definition, platform.entryValue());
        final int spaceStrictness = PolicyCatalog.strictnessOf(definition, spaceRow.entryValue());
        if (spaceStrictness > platformStrictness) {
            return new EffectivePolicyRow(key, definition.displayName(), platform.redline(),
                    platform.entryValue(), spaceRow.entryValue(), spaceRow.status(), spaceRow.entryValue(),
                    PolicySource.SPACE, PolicyProvenance.SPACE_EFFECTIVE,
                    "空间级覆盖更严，生效空间级值");
        }
        if (spaceStrictness < platformStrictness) {
            return new EffectivePolicyRow(key, definition.displayName(), platform.redline(),
                    platform.entryValue(), spaceRow.entryValue(), spaceRow.status(), platform.entryValue(),
                    PolicySource.PLATFORM, PolicyProvenance.SPACE_NOT_EFFECTIVE_TAKE_STRICTER,
                    "依据 " + key + " 严格度：平台级 " + platform.entryValue() + "(严) 优于空间级 "
                            + spaceRow.entryValue() + "(宽)，取平台级");
        }
        return new EffectivePolicyRow(key, definition.displayName(), platform.redline(),
                platform.entryValue(), spaceRow.entryValue(), spaceRow.status(), spaceRow.entryValue(),
                PolicySource.SPACE, PolicyProvenance.SPACE_EFFECTIVE,
                "空间级显式配置（与平台级同值），生效空间级值");
    }
}
