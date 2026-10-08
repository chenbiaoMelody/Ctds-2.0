package com.ctds.contract.application;

import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.PolicyElementCatalog;
import com.ctds.contract.domain.policy.PolicyViolation;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsagePolicyDsl;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内置双向场景集（WBS-3.4.6 hifi §3，Q4-A——规格行为 5 规则 4 双向用例硬约束的模拟器形态承载）：
 * 目录驱动 11 条（五要素 × 双向 10 条 + 空策略/显式无限制 1 条）。
 *
 * <p><b>同源约束</b>（承接 3.4.4 移交-2）：场景按 {@code PolicyElementCatalog.keys()} 声明序生成，
 * 目录扩要素而本类未同步 → 计划阶段 fail-fast（结构锚测试红）；场景构造与预期按要素语义解释，
 * <b>不复制要素语义</b>——限值取策略 {@code quota.maxCount}、期限取策略起止日、用途/域取策略文本。
 * 每个 DENY 场景只在自身要素上越界，其余要素取"合规基准值"（期限要素启用则基准日 = 起始日、
 * 计数基准 = 0、用途/域基准 = 策略文本、动作基准 = USE），故触发要素集合唯一。</p>
 */
public final class PolicyTestbenchScenarios {

    /** 场景方向（双向用例硬约束：合规放行 / 越界拒绝）。 */
    public enum Direction {

        /** 合规方向（期望放行）。 */
        ALLOW,
        /** 越界方向（期望拒绝且触发要素命中）。 */
        DENY
    }

    /** 合规方向期望文案（放行）。 */
    static final String EXPECTATION_ALLOW = "放行";

    /** 空策略/显式无限制场景的期望文案（无要素可判——放行且不计数）。 */
    static final String EXPECTATION_BLANK_ALLOW = "放行（无使用限制）";

    /** 要素未启用场景的期望文案（诚实不假绿——既不判通过也不判失败）。 */
    static final String EXPECTATION_DISABLED = "要素未启用";

    /** 越界取值后缀（hifi §3 场景表：约定值 + {@code -越界}，与约定值精确等值比较必不等）。 */
    private static final String MISMATCH_SUFFIX = "-越界";

    private PolicyTestbenchScenarios() {
    }

    /**
     * 计划场景集（纯函数，无 IO）：按目录声明序 → 每要素两向 → 末尾一条"空策略/显式无限制"。
     *
     * @param strategy 被测策略（可为 null = 无策略；要素一律记不适用）
     * @param today    注入 Clock 当天（期限要素未启用时的基准判定日期；禁止直取 LocalDate.now()）
     */
    public static List<PlannedScenario> plan(final UsageControlPolicy strategy, final LocalDate today) {
        Objects.requireNonNull(today, "today 必填");
        final Baseline baseline = baseline(strategy, today);
        final List<PlannedScenario> plans = new ArrayList<>();
        for (final String key : PolicyElementCatalog.keys()) {
            final PolicyElementCatalog.ElementDefinition definition = PolicyElementCatalog.find(key)
                    .orElseThrow(() -> new IllegalStateException("目录键未注册: " + key));
            final UsageControlPolicy.Element element = elementOf(strategy, definition.field());
            final boolean enabled = element != null && element.enabled();
            for (final Direction direction : directionsOf(definition.field())) {
                plans.add(scenario(definition, element, enabled, direction, baseline, plans.size() + 1));
            }
        }
        // 空策略/显式无限制单场景（适用前提 = 无启用要素或显式声明——不适用则 SKIPPED）
        plans.add(new PlannedScenario("U" + (plans.size() + 1), null, Direction.ALLOW,
                EXPECTATION_BLANK_ALLOW, !anyElementEnabled(strategy), baseline.actionType(),
                baseline.purpose(), baseline.territory(), baseline.assumedUsedCount(),
                baseline.assumedDate(), null));
        return List.copyOf(plans);
    }

    /**
     * 目录字段名 → 策略要素访问器（<b>模拟器内单点</b>；快照装配与场景集共用，禁止第二处并行映射）。
     * 语义定义权威 = {@link PolicyElementCatalog}；未注册字段返回 null（不适用）。
     */
    public static UsageControlPolicy.Element elementOf(final UsageControlPolicy policy,
            final String field) {
        if (policy == null) {
            return null;
        }
        return switch (field) {
            case UsagePolicyDsl.FIELD_QUOTA -> policy.quota();
            case UsagePolicyDsl.FIELD_TERM -> policy.term();
            case UsagePolicyDsl.FIELD_PURPOSE -> policy.purpose();
            case UsagePolicyDsl.FIELD_TERRITORY -> policy.territory();
            case UsagePolicyDsl.FIELD_NO_REDISTRIBUTION -> policy.noRedistribution();
            default -> null;
        };
    }

    /** 策略是否"无使用限制"（无启用要素或显式声明——空策略场景的适用前提）。 */
    static boolean anyElementEnabled(final UsageControlPolicy policy) {
        if (policy == null || policy.noRestrictionDeclared()) {
            return false;
        }
        for (final String key : PolicyElementCatalog.keys()) {
            final PolicyElementCatalog.ElementDefinition definition = PolicyElementCatalog.find(key)
                    .orElseThrow();
            final UsageControlPolicy.Element element = elementOf(policy, definition.field());
            if (element != null && element.enabled()) {
                return true;
            }
        }
        return false;
    }

    private static PlannedScenario scenario(final PolicyElementCatalog.ElementDefinition definition,
            final UsageControlPolicy.Element element, final boolean enabled,
            final Direction direction, final Baseline baseline, final int index) {
        final String code = "U" + index;
        if (!enabled) {
            return new PlannedScenario(code, definition.key(), direction, EXPECTATION_DISABLED, false,
                    baseline.actionType(), baseline.purpose(), baseline.territory(),
                    baseline.assumedUsedCount(), baseline.assumedDate(), null);
        }
        final boolean allow = direction == Direction.ALLOW;
        return switch (definition.field()) {
            case UsagePolicyDsl.FIELD_QUOTA -> new PlannedScenario(code, definition.key(), direction,
                    allow ? EXPECTATION_ALLOW : "拒绝（次数耗尽）", true, baseline.actionType(),
                    baseline.purpose(), baseline.territory(),
                    allow ? element.maxCount() - 1 : element.maxCount(), baseline.assumedDate(),
                    allow ? null : PolicyViolation.QUOTA_EXHAUSTED);
            case UsagePolicyDsl.FIELD_TERM -> new PlannedScenario(code, definition.key(), direction,
                    allow ? EXPECTATION_ALLOW : "拒绝（期限届满）", true, baseline.actionType(),
                    baseline.purpose(), baseline.territory(), baseline.assumedUsedCount(),
                    allow ? LocalDate.parse(element.startDate())
                            : LocalDate.parse(element.endDate()).plusDays(1),
                    allow ? null : PolicyViolation.TERM_EXPIRED);
            case UsagePolicyDsl.FIELD_PURPOSE -> new PlannedScenario(code, definition.key(), direction,
                    allow ? EXPECTATION_ALLOW : "拒绝（用途不符）", true, baseline.actionType(),
                    allow ? element.text() : element.text() + MISMATCH_SUFFIX, baseline.territory(),
                    baseline.assumedUsedCount(), baseline.assumedDate(),
                    allow ? null : PolicyViolation.PURPOSE_MISMATCH);
            case UsagePolicyDsl.FIELD_TERRITORY -> new PlannedScenario(code, definition.key(), direction,
                    allow ? EXPECTATION_ALLOW : "拒绝（域外使用）", true, baseline.actionType(),
                    baseline.purpose(),
                    allow ? element.text() : element.text() + MISMATCH_SUFFIX,
                    baseline.assumedUsedCount(), baseline.assumedDate(),
                    allow ? null : PolicyViolation.TERRITORY_MISMATCH);
            case UsagePolicyDsl.FIELD_NO_REDISTRIBUTION -> new PlannedScenario(code, definition.key(),
                    direction, allow ? EXPECTATION_ALLOW : "拒绝（禁止再分发）", true,
                    allow ? UsageActionType.USE : UsageActionType.REDISTRIBUTE, baseline.purpose(),
                    baseline.territory(), baseline.assumedUsedCount(), baseline.assumedDate(),
                    allow ? null : PolicyViolation.REDISTRIBUTION_FORBIDDEN);
            default -> throw new IllegalStateException("目录要素未配置场景: " + definition.key());
        };
    }

    /** 方向次序（目录声明序遍历；再分发要素先 DENY 后 ALLOW——沿 hifi §3 场景表 U1~U10 行序）。 */
    private static List<Direction> directionsOf(final String field) {
        return UsagePolicyDsl.FIELD_NO_REDISTRIBUTION.equals(field)
                ? List.of(Direction.DENY, Direction.ALLOW)
                : List.of(Direction.ALLOW, Direction.DENY);
    }

    /** 合规基准上下文（期限要素启用 → 基准日 = 起始日，恒在 [起始日, 截止日] 内）。 */
    private static Baseline baseline(final UsageControlPolicy strategy, final LocalDate today) {
        final UsageControlPolicy.Element term = elementOf(strategy, UsagePolicyDsl.FIELD_TERM);
        final LocalDate baselineDate = term != null && term.enabled()
                ? LocalDate.parse(term.startDate()) : today;
        return new Baseline(UsageActionType.USE,
                textOf(elementOf(strategy, UsagePolicyDsl.FIELD_PURPOSE)),
                textOf(elementOf(strategy, UsagePolicyDsl.FIELD_TERRITORY)), 0, baselineDate);
    }

    private static String textOf(final UsageControlPolicy.Element element) {
        return element != null && element.enabled() ? element.text() : null;
    }

    /** 基准上下文（各场景只在自身要素上偏离基准）。 */
    private record Baseline(UsageActionType actionType, String purpose, String territory,
            int assumedUsedCount, LocalDate assumedDate) {
    }

    /**
     * 场景计划项（供编排层逐条走模拟通道；零副作用）。
     *
     * @param code              场景码（U1~U11，沿 hifi §3 场景表）
     * @param elementKey        目录键（空策略场景为 null）
     * @param direction         方向（ALLOW / DENY）
     * @param expectation       期望文案（人可读）
     * @param applicable        场景适用前提是否成立（要素未启用 / 策略非空 → false = SKIPPED）
     * @param actionType        构造动作类型
     * @param purpose           构造用途（可空）
     * @param territory         构造域（可空）
     * @param assumedUsedCount  构造假想已用次数
     * @param assumedDate       构造假想判定日期
     * @param expectedViolation 越界方向期望触发的要素码（ALLOW 方向/不适用为 null）
     */
    public record PlannedScenario(String code, String elementKey, Direction direction,
            String expectation, boolean applicable, UsageActionType actionType, String purpose,
            String territory, int assumedUsedCount, LocalDate assumedDate,
            PolicyViolation expectedViolation) {
    }
}
