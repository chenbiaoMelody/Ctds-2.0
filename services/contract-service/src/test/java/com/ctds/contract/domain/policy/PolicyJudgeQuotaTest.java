package com.ctds.contract.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.UsageControlPolicy;
import org.junit.jupiter.api.Test;

/**
 * 配额纯判定单测（WBS-3.4.6 hifi §5 / §9①）：限值来源 = 策略 {@code quota.maxCount}、耗尽判据 =
 * {@code assumedUsedCount >= limit}（与真实执行腿 {@code tryIncrement} 条件 UPDATE 同源）；
 * 要素未启用 / 空策略 / 显式无限制 → 不判（空列表）。
 */
class PolicyJudgeQuotaTest {

    private static final int LIMIT = 100;

    @Test
    void quotaDisabledOrBlankPolicyYieldsNoViolation() {
        assertThat(PolicyJudge.judgeQuota(UsageControlPolicy.empty(), LIMIT)).isEmpty();
        assertThat(PolicyJudge.judgeQuota(quota(false, LIMIT), LIMIT)).isEmpty();
        assertThat(PolicyJudge.judgeQuota(quota(false, null), LIMIT + 1)).isEmpty();
    }

    @Test
    void explicitNoRestrictionYieldsNoViolation() {
        final UsageControlPolicy declared = new UsageControlPolicy(
                UsageControlPolicy.Element.ofCount(false, LIMIT),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), true);
        assertThat(PolicyJudge.judgeQuota(declared, LIMIT)).isEmpty();
    }

    @Test
    void assumedCountBelowLimitPasses() {
        assertThat(PolicyJudge.judgeQuota(quota(true, LIMIT), 0)).isEmpty();
        assertThat(PolicyJudge.judgeQuota(quota(true, LIMIT), LIMIT - 1)).isEmpty();
        assertThat(PolicyJudge.judgeQuota(quota(true, 1), 0)).isEmpty();
    }

    @Test
    void assumedCountAtAndBeyondLimitViolates() {
        assertThat(PolicyJudge.judgeQuota(quota(true, LIMIT), LIMIT))
                .containsExactly(PolicyViolation.QUOTA_EXHAUSTED);
        assertThat(PolicyJudge.judgeQuota(quota(true, LIMIT), LIMIT + 1))
                .containsExactly(PolicyViolation.QUOTA_EXHAUSTED);
        assertThat(PolicyJudge.judgeQuota(quota(true, 1), 1))
                .containsExactly(PolicyViolation.QUOTA_EXHAUSTED);
    }

    private static UsageControlPolicy quota(final boolean enabled, final Integer maxCount) {
        return new UsageControlPolicy(UsageControlPolicy.Element.ofCount(enabled, maxCount),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                false);
    }
}
