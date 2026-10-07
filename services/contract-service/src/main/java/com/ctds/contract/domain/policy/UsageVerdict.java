package com.ctds.contract.domain.policy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 判定结果值对象（WBS-3.4.5 hifi §2）：放行时携带递增后已用次数；拒绝时（{@code allowed =
 * false}）携带触发要素全查明细（一次请求触犯多项全部报告——全查口径）。判定时点取注入
 * {@code Clock}（两把钟教训——禁止直取 LocalDateTime.now()）。
 *
 * @param allowed    放行/拒绝
 * @param usedCount  放行后已用次数（拒绝时 = 当前计数不变值；无配额口径 = 0）
 * @param violations 拒绝时触发要素明细（放行时为空列表）
 * @param occurredAt 判定时点（注入 Clock）
 */
public record UsageVerdict(boolean allowed, int usedCount, List<PolicyViolation> violations,
        LocalDateTime occurredAt) {

    /** 放行结果（violations 恒空——不可变列表）。 */
    public static UsageVerdict allowed(final int usedCount, final LocalDateTime occurredAt) {
        return new UsageVerdict(true, usedCount, List.of(), occurredAt);
    }

    /** 拒绝结果（全查明细——不可变列表）。 */
    public static UsageVerdict denied(final int currentCount,
            final List<PolicyViolation> violations, final LocalDateTime occurredAt) {
        return new UsageVerdict(false, currentCount, List.copyOf(violations), occurredAt);
    }
}
