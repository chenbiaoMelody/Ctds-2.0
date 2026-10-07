package com.ctds.contract.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.UsageControlPolicy;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 判定纯函数矩阵单测（WBS-3.4.5 hifi §9 ①）：四要素（期限/用途/域/再分发）× 生效/越界
 * 双向 + 边界（期限首末日有效/期外首日拒绝、trim 对称、大小写敏感、全查明细、禁用要素不参与）。
 * 次数要素（quota）不在本函数——归判定步 9 判检一体（仓储层）。
 */
class PolicyJudgeTest {

    private static final LocalDate IN_TERM = LocalDate.of(2027, 6, 15);
    private static final LocalDate START = LocalDate.of(2027, 1, 1);
    private static final LocalDate END = LocalDate.of(2027, 12, 31);
    private static final String PURPOSE_TEXT = "风控建模";
    private static final String TERRITORY_TEXT = "本市域";

    /** 期限+用途+域+再分发四要素全启用（次数要素与本函数无关，置禁用）。 */
    private static UsageControlPolicy fullPolicy() {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofTerm(true, START.toString(), END.toString()),
                UsageControlPolicy.Element.ofText(true, PURPOSE_TEXT),
                UsageControlPolicy.Element.ofText(true, TERRITORY_TEXT),
                UsageControlPolicy.Element.flag(true), false);
    }

    private static UsageRequest validRequest() {
        return new UsageRequest("S-req", UsageActionType.USE, PURPOSE_TEXT, TERRITORY_TEXT);
    }

    /** 仅用途要素启用（大小写敏感性对照用——其余要素禁用不干扰判定）。 */
    private static UsageControlPolicy purposeOnlyPolicy(final String text) {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofText(true, text),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), false);
    }

    // ==== 期限（步 4；规格验收标准 2）====

    @Test
    void termWithinRangeIncludingBothBoundaryDaysPasses() {
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), IN_TERM)).isEmpty();
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), START)).isEmpty();
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), END)).isEmpty();
    }

    @Test
    void termOutsideRangeOnDayBeforeStartOrAfterEndTriggersTermExpired() {
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), START.minusDays(1)))
                .containsExactly(PolicyViolation.TERM_EXPIRED);
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), END.plusDays(1)))
                .containsExactly(PolicyViolation.TERM_EXPIRED);
    }

    // ==== 用途（步 5；规格验收标准 3）====

    @Test
    void purposeExactMatchPassesAndMismatchOrAbsentTriggers() {
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), IN_TERM)).isEmpty();
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, "政策研究", TERRITORY_TEXT),
                IN_TERM)).containsExactly(PolicyViolation.PURPOSE_MISMATCH);
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, null, TERRITORY_TEXT), IN_TERM))
                .containsExactly(PolicyViolation.PURPOSE_MISMATCH);
    }

    @Test
    void purposeComparisonTrimsRequestSideAndIsCaseSensitive() {
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, "  " + PURPOSE_TEXT + "  ",
                        TERRITORY_TEXT), IN_TERM))
                .as("请求侧 trim 对称（策略侧已由 3.4.4 写入时规范化）").isEmpty();
        // 大小写敏感性以拉丁文本对照验证（中文无大小写概念——避免无效断言）
        final UsageControlPolicy latin = purposeOnlyPolicy("RiskModel");
        assertThat(PolicyJudge.judge(latin,
                new UsageRequest("S-req", UsageActionType.USE, "riskmodel", null), IN_TERM))
                .as("精确等值 = 大小写敏感").containsExactly(PolicyViolation.PURPOSE_MISMATCH);
        assertThat(PolicyJudge.judge(latin,
                new UsageRequest("S-req", UsageActionType.USE, "RiskModel", null), IN_TERM))
                .isEmpty();
    }

    // ==== 域内（步 6；规格验收标准 4）====

    @Test
    void territoryExactMatchPassesAndMismatchOrAbsentTriggers() {
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), IN_TERM)).isEmpty();
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, PURPOSE_TEXT, "境外"),
                IN_TERM)).containsExactly(PolicyViolation.TERRITORY_MISMATCH);
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, PURPOSE_TEXT, null), IN_TERM))
                .containsExactly(PolicyViolation.TERRITORY_MISMATCH);
    }

    @Test
    void territoryComparisonTrimsRequestSide() {
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.USE, PURPOSE_TEXT,
                        "  " + TERRITORY_TEXT + " "), IN_TERM)).isEmpty();
    }

    // ==== 再分发（步 7；规格验收标准 5）====

    @Test
    void redistributionBlockedWhenEnabledAndActionIsRedistribute() {
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.REDISTRIBUTE, PURPOSE_TEXT,
                        TERRITORY_TEXT), IN_TERM))
                .containsExactly(PolicyViolation.REDISTRIBUTION_FORBIDDEN);
    }

    @Test
    void redistributionNotBlockedWhenDisabledOrActionIsUse() {
        assertThat(PolicyJudge.judge(fullPolicy(), validRequest(), IN_TERM))
                .as("启用禁止再分发不拦 USE 动作").isEmpty();
        final UsageControlPolicy notForbidden = new UsageControlPolicy(
                UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofTerm(true, START.toString(), END.toString()),
                UsageControlPolicy.Element.ofText(true, PURPOSE_TEXT),
                UsageControlPolicy.Element.ofText(true, TERRITORY_TEXT),
                UsageControlPolicy.Element.flag(false), false);
        assertThat(PolicyJudge.judge(notForbidden,
                new UsageRequest("S-req", UsageActionType.REDISTRIBUTE, PURPOSE_TEXT,
                        TERRITORY_TEXT), IN_TERM))
                .as("未启用禁止再分发不拦 REDISTRIBUTE 动作").isEmpty();
    }

    // ==== 全查明细与禁用要素（hifi §3 步 8 全查口径 / §2 violations）====

    @Test
    void fullScanReportsAllTriggeredViolationsInJudgmentOrder() {
        assertThat(PolicyJudge.judge(fullPolicy(),
                new UsageRequest("S-req", UsageActionType.REDISTRIBUTE, "政策研究", "境外"),
                END.plusDays(1)))
                .containsExactly(PolicyViolation.TERM_EXPIRED, PolicyViolation.PURPOSE_MISMATCH,
                        PolicyViolation.TERRITORY_MISMATCH,
                        PolicyViolation.REDISTRIBUTION_FORBIDDEN);
    }

    @Test
    void disabledElementsDoNotParticipateInJudgment() {
        final UsageControlPolicy allDisabled = UsageControlPolicy.empty();
        assertThat(PolicyJudge.judge(allDisabled,
                new UsageRequest("S-req", UsageActionType.REDISTRIBUTE, "任意用途", "任意地域"),
                END.plusDays(30))).isEmpty();
    }
}
