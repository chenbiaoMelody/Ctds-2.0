package com.ctds.space.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 空间策略条目目录（WBS-3.2.5 hifi §3，规格行为 7 规则 6"条目键命名与可配置项集合随 3.2.5 定稿"）：
 * 键 → 显示名 → 封闭值域（值 → 严格度，单射）的静态注册表，空间域策略条目语言的权威定义点。
 *
 * <p>严格度数值越大越严（对齐数据分类分级"就高"原则，行为 7 规则 2/3 的放宽/取严判定依据）；
 * 同键值域内严格度单射（无并列值——同严格度即同值，消除平局歧义）。红线标记沿用载体列
 * is_redline（仅平台级条目可标，WBS-3.2.2 hifi §1.5）。扩目录 = 本注册表追加一行登记
 * （键命名规范：域.名词小写点分）；本类与 C-4.x 使用控制策略模型对齐复用（规格行为 7 规则 6
 * 禁止两处各自定义——对齐义务见任务卡 §四移交）。</p>
 */
public final class PolicyCatalog {

    /** 单键定义：键、业务显示名、封闭值域（值 → 严格度，按声明序）。 */
    public record EntryDefinition(String key, String displayName, Map<String, Integer> strictness) {

        /** 值是否在该键封闭值域内。 */
        public boolean containsValue(final String value) {
            return strictness.containsKey(value);
        }
    }

    private static final Map<String, EntryDefinition> ENTRIES = new LinkedHashMap<>();

    static {
        register(new EntryDefinition("data.visibility", "数据可见范围",
                Map.of("SPACE_MEMBER", 2, "ALL_PLATFORM", 1)));
        register(new EntryDefinition("data.retention", "数据留存期限",
                Map.of("D30", 4, "D90", 3, "D180", 2, "D365", 1)));
        register(new EntryDefinition("member.data_export", "成员数据导出",
                Map.of("FORBIDDEN", 3, "APPROVAL_REQUIRED", 2, "ALLOWED", 1)));
    }

    private PolicyCatalog() {
    }

    private static void register(final EntryDefinition definition) {
        ENTRIES.put(definition.key(), definition);
    }

    /** 按键取定义（未注册键返回 empty——调用方转 1006C0012）。 */
    public static Optional<EntryDefinition> find(final String key) {
        return Optional.ofNullable(ENTRIES.get(key));
    }

    /** 键是否已注册（目录封闭性判定）。 */
    public static boolean isDefined(final String key) {
        return ENTRIES.containsKey(key);
    }

    /** 已注册键集合（按声明序——有效策略视图的稳定排序依据）。 */
    public static Set<String> keys() {
        return Collections.unmodifiableSet(ENTRIES.keySet());
    }

    /**
     * 取值严格度（值须在该键值域内——越域抛 1006C0012 兜底，不做 NPE：写路径已先行值域校验，
     * 此门槛防"库内数据与目录漂移"的防御纵深，4 视角评审②/④跟踪项收口）。
     */
    public static int strictnessOf(final EntryDefinition definition, final String value) {
        final Integer strictness = definition.strictness().get(value);
        if (strictness == null) {
            throw new SpaceBizException(SpaceErrorCodes.POLICY_ENTRY_INVALID,
                    SpaceErrorCodes.POLICY_ENTRY_INVALID_MESSAGE);
        }
        return strictness;
    }

    /**
     * 放宽判定（行为 7 规则 2 写门核心，代码强制 + 反向探针 T2）：to 值严格度低于 from 值 = 放宽。
     * 两值均须在该键值域内（调用方先行值域校验）。
     */
    public static boolean isLoosening(final EntryDefinition definition, final String fromValue,
            final String toValue) {
        return strictnessOf(definition, toValue) < strictnessOf(definition, fromValue);
    }
}
