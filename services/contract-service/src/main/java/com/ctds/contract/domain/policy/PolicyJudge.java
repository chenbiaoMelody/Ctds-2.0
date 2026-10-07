package com.ctds.contract.domain.policy;

import com.ctds.contract.domain.UsageControlPolicy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 判定纯函数（WBS-3.4.5 hifi §3 判定顺序表步 4~7）：期限/用途/域/再分发四要素全查——
 * 一次请求触犯多项全部报告（无短路；配额判检一体的次数要素归步 9 仓储层，不在本函数）。
 *
 * <p>判定语义唯一权威 = {@link PolicyElementCatalog#judgmentSemantics()}（3.4.4 交付的
 * 契约面——引擎消费不复制，禁止两处并行定义）：期限 = 区间含首末日；用途/域 = 精确等值
 * （大小写敏感，策略侧文本已由 3.4.4 trim 规范化，请求侧同样 trim 后比较——口径对称）；
 * 再分发 = 启用即拦 REDISTRIBUTE 动作。无 IO、无时钟直取（当前日期由调用方注入）。</p>
 */
public final class PolicyJudge {

    private PolicyJudge() {
    }

    /**
     * 全查判定（步 4~7）：返回触发要素明细（空列表 = 全部通过）。明细顺序 = 判定步序
     * （期限 → 用途 → 域 → 再分发），供拒绝留痕与日志的稳定呈现。
     *
     * @param policy  生效策略值对象（禁用要素不参与判定；目录键见 PolicyElementCatalog）
     * @param request 使用请求（请求侧文本 trim 后与策略文本比较——口径对称）
     * @param today   判定当前日期（注入 Clock 派生——禁止直取 LocalDate.now()）
     */
    public static List<PolicyViolation> judge(final UsageControlPolicy policy,
            final UsageRequest request, final LocalDate today) {
        final List<PolicyViolation> violations = new ArrayList<>();
        // 步 4 期限（目录键 usage.term：区间含首末日）
        if (enabled(policy.term())) {
            final LocalDate start = LocalDate.parse(policy.term().startDate());
            final LocalDate end = LocalDate.parse(policy.term().endDate());
            if (today.isBefore(start) || today.isAfter(end)) {
                violations.add(PolicyViolation.TERM_EXPIRED);
            }
        }
        // 步 5 用途（目录键 usage.purpose：精确等值——大小写敏感、请求侧 trim 对称）
        if (enabled(policy.purpose())
                && !Objects.equals(trim(request.purpose()), policy.purpose().text())) {
            violations.add(PolicyViolation.PURPOSE_MISMATCH);
        }
        // 步 6 域内（目录键 usage.territory：精确等值）
        if (enabled(policy.territory())
                && !Objects.equals(trim(request.territory()), policy.territory().text())) {
            violations.add(PolicyViolation.TERRITORY_MISMATCH);
        }
        // 步 7 再分发（目录键 usage.no_redistribution：启用即拦 REDISTRIBUTE 动作）
        if (enabled(policy.noRedistribution())
                && request.actionType() == UsageActionType.REDISTRIBUTE) {
            violations.add(PolicyViolation.REDISTRIBUTION_FORBIDDEN);
        }
        return violations;
    }

    private static boolean enabled(final UsageControlPolicy.Element element) {
        return element != null && element.enabled();
    }

    private static String trim(final String text) {
        return text == null ? null : text.trim();
    }
}
