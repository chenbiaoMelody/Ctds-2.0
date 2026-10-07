package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsageLogEntry;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.ctds.contract.infrastructure.UsageCounterStore;
import com.ctds.contract.infrastructure.UsageLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 判定编排层单测（WBS-3.4.5 hifi §9 ①）：状态门槛（C0012/C0013）、空策略/显式无限制放行
 * 不计数、拒绝不烧次数（配额判检前不触碰 counter）、判检一体耗尽、注入偏移钟跨期限边界、
 * 拒绝留痕触发要素明细。判定纯函数矩阵归 {@link com.ctds.contract.domain.policy.PolicyJudgeTest}。
 */
@ExtendWith(MockitoExtension.class)
class PolicyExecutionServiceTest {

    private static final String CONTRACT_NO = "CO000001";
    private static final String REQUESTER = "S-req";
    /** 判定时点锚（Asia/Shanghai → 2027-06-15 18:00）。 */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2027-06-15T10:00:00Z"),
            ZoneId.of("Asia/Shanghai"));
    private static final Instant OFFSET_INSTANT = Instant.parse("2028-06-15T10:00:00Z");
    private static final String C0012 = "1008C0012";
    private static final String C0013 = "1008C0013";
    private static final String C0020 = "1008C0020";

    @Mock
    private ContractQueryService contractQueryService;
    @Mock
    private UsageCounterStore counterStore;
    @Mock
    private UsageLogRepository logRepository;

    private PolicyExecutionService service;

    @BeforeEach
    void setUp() {
        service = new PolicyExecutionService(contractQueryService, counterStore, logRepository,
                FIXED);
    }

    // ==== 步 1 合约定位（C0012 防枚举同形——零副作用）====

    @Test
    void notExistingContractThrowsC0012WithoutAnySideEffect() {
        given(contractQueryService.loadEffectiveStrategy(CONTRACT_NO)).willReturn(null);
        assertThatThrownBy(() -> service.check(CONTRACT_NO, useRequest(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0012);
        then(counterStore).shouldHaveNoInteractions();
        then(logRepository).shouldHaveNoInteractions();
    }

    // ==== 步 2 状态门槛（移交-5 承接：未生效/终止/完结 → C0013 + 拒绝留痕）====

    @Test
    void notYetEffectiveContractThrowsC0013WithDeniedLog() {
        stubSnapshot("NEGOTIATING", null);
        assertThrowsC0013();
        final UsageLogEntry denial = capturedDenial();
        assertThat(denial.outcome()).isEqualTo(UsageLogEntry.UsageOutcome.DENIED);
        assertThat(denial.reasonCode()).isEqualTo("C0013");
        assertThat(denial.violations()).isNull();
        assertThat(denial.usedCount()).isZero();
        then(counterStore).should(never()).tryIncrement(anyString(), anyInt(), any());
    }

    @Test
    void terminatedContractThrowsC0013WithDeniedLog() {
        stubSnapshot("TERMINATED", null);
        // 历史用量 3 次（计数行已存在）——拒绝留痕须落当前不变计数快照（hifi §5 used_count 口径）
        given(counterStore.currentCount(CONTRACT_NO)).willReturn(3);
        assertThrowsC0013();
        final UsageLogEntry denial = capturedDenial();
        assertThat(denial.reasonCode()).isEqualTo("C0013");
        assertThat(denial.usedCount()).as("状态门槛腿留痕 = 当前不变计数快照").isEqualTo(3);
    }

    @Test
    void completedContractThrowsC0013WithDeniedLog() {
        stubSnapshot("COMPLETED", null);
        assertThrowsC0013();
        assertThat(capturedDenial().reasonCode()).isEqualTo("C0013");
    }

    // ==== 步 3 空策略 / 显式无限制（放行不计数）====

    @Test
    void emptyStrategyAllowedWithoutCounting() {
        stubSnapshot("EFFECTIVE", null);
        final UsageVerdict verdict = service.check(CONTRACT_NO, useRequest(null, null));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.usedCount()).isZero();
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.occurredAt()).isEqualTo(LocalDateTime.now(FIXED));
        then(counterStore).shouldHaveNoInteractions();
        assertThat(capturedAllow().outcome()).isEqualTo(UsageLogEntry.UsageOutcome.ALLOWED);
    }

    @Test
    void explicitNoRestrictionDeclarationAllowedWithoutCounting() {
        stubSnapshot("EFFECTIVE", declaredNoRestriction());
        final UsageVerdict verdict = service.check(CONTRACT_NO, useRequest("任意用途", "任意地域"));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.usedCount()).isZero();
        then(counterStore).shouldHaveNoInteractions();
        assertThat(capturedAllow().usedCount()).isZero();
    }

    // ==== 步 9/10 配额判检一体（放行才计数）====

    @Test
    void quotaEnabledAllowedIncrementsCountAndWritesAllowedLog() {
        stubSnapshot("EFFECTIVE", quotaPolicy(3));
        given(counterStore.tryIncrement(eq(CONTRACT_NO), eq(3), any(LocalDateTime.class)))
                .willReturn(2);
        final UsageVerdict verdict = service.check(CONTRACT_NO, useRequest(null, null));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.usedCount()).isEqualTo(2);
        final UsageLogEntry allow = capturedAllow();
        assertThat(allow.outcome()).isEqualTo(UsageLogEntry.UsageOutcome.ALLOWED);
        assertThat(allow.reasonCode()).isNull();
        assertThat(allow.usedCount()).isEqualTo(2);
        then(logRepository).should(never()).insertDenied(any());
    }

    @Test
    void quotaDisabledWithMatchingTextElementsAllowedWithoutCounting() {
        stubSnapshot("EFFECTIVE", purposePolicy("风控建模"));
        final UsageVerdict verdict = service.check(CONTRACT_NO, useRequest("风控建模", null));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.usedCount()).isZero();
        then(counterStore).should(never()).tryIncrement(anyString(), anyInt(), any());
        assertThat(capturedAllow().usedCount()).isZero();
    }

    // ==== 步 8 全查拒绝（拒绝不烧次数——配额判检前零触碰 counter）====

    @Test
    void purposeMismatchThrowsC0020WithViolationAndWithoutTouchingCounter() {
        stubSnapshot("EFFECTIVE", quotaAndPurposePolicy(5, "风控建模"));
        given(counterStore.currentCount(CONTRACT_NO)).willReturn(1);
        assertThatThrownBy(() -> service.check(CONTRACT_NO, useRequest("政策研究", null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0020);
        final UsageLogEntry denial = capturedDenial();
        assertThat(denial.reasonCode()).isEqualTo("C0020");
        assertThat(denial.violations()).isEqualTo("PURPOSE_MISMATCH");
        assertThat(denial.usedCount()).as("拒绝不烧次数——留痕落当前不变值").isEqualTo(1);
        then(counterStore).should(never()).tryIncrement(anyString(), anyInt(), any());
        then(logRepository).should(never()).insertAllowed(any());
    }

    @Test
    void redistributionActionBlockedWithViolation() {
        stubSnapshot("EFFECTIVE", noRedistributionPolicy());
        assertThatThrownBy(() -> service.check(CONTRACT_NO, new UsageRequest(REQUESTER,
                UsageActionType.REDISTRIBUTE, null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0020);
        assertThat(capturedDenial().violations()).isEqualTo("REDISTRIBUTION_FORBIDDEN");
    }

    // ==== 步 9 耗尽（判检一体影响行数 = 0 → QUOTA_EXHAUSTED）====

    @Test
    void quotaExhaustedThrowsC0020WithQuotaExhaustedViolationAndCurrentCount() {
        stubSnapshot("EFFECTIVE", quotaPolicy(1));
        given(counterStore.tryIncrement(eq(CONTRACT_NO), eq(1), any(LocalDateTime.class)))
                .willReturn(null);
        given(counterStore.currentCount(CONTRACT_NO)).willReturn(1);
        assertThatThrownBy(() -> service.check(CONTRACT_NO, useRequest(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0020);
        final UsageLogEntry denial = capturedDenial();
        assertThat(denial.violations()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(denial.usedCount()).isEqualTo(1);
        then(logRepository).should(never()).insertAllowed(any());
    }

    // ==== 注入偏移钟跨期限边界（两把钟教训——判定日期取注入 Clock）====

    @Test
    void offsetClockAcrossTermBoundaryDecidesByInjectedDate() {
        // 策略期限 2028-01-01 ~ 2028-12-31：基准钟（2027）在期外 → 拒绝；偏移钟（2028）在期内 → 放行
        stubSnapshot("EFFECTIVE", termPolicy("2028-01-01", "2028-12-31"));
        assertThatThrownBy(() -> service.check(CONTRACT_NO, useRequest(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0020);
        assertThat(capturedDenial().violations()).isEqualTo("TERM_EXPIRED");
        final PolicyExecutionService offsetService = new PolicyExecutionService(
                contractQueryService, counterStore, logRepository,
                Clock.fixed(OFFSET_INSTANT, ZoneId.of("Asia/Shanghai")));
        final UsageVerdict verdict = offsetService.check(CONTRACT_NO, useRequest(null, null));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.occurredAt())
                .isEqualTo(LocalDateTime.now(Clock.fixed(OFFSET_INSTANT, ZoneId.of("Asia/Shanghai"))));
        then(logRepository).should(times(1)).insertAllowed(any());
    }

    // ==== 支撑（策略构造 / 桩 / 捕获）====

    private void stubSnapshot(final String status, final UsageControlPolicy strategy) {
        given(contractQueryService.loadEffectiveStrategy(CONTRACT_NO)).willReturn(
                new ContractQueryService.ContractStrategySnapshot(CONTRACT_NO, status,
                        LocalDateTime.of(2027, 1, 1, 0, 0), strategy));
    }

    private void assertThrowsC0013() {
        assertThatThrownBy(() -> service.check(CONTRACT_NO, useRequest(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ex -> ((ContractBizException) ex).getErrorCode().value())
                .isEqualTo(C0013);
    }

    private UsageLogEntry capturedDenial() {
        final ArgumentCaptor<UsageLogEntry> captor = ArgumentCaptor.forClass(UsageLogEntry.class);
        then(logRepository).should(times(1)).insertDenied(captor.capture());
        return captor.getValue();
    }

    private UsageLogEntry capturedAllow() {
        final ArgumentCaptor<UsageLogEntry> captor = ArgumentCaptor.forClass(UsageLogEntry.class);
        then(logRepository).should(times(1)).insertAllowed(captor.capture());
        return captor.getValue();
    }

    private static UsageRequest useRequest(final String purpose, final String territory) {
        return new UsageRequest(REQUESTER, UsageActionType.USE, purpose, territory);
    }

    private static UsageControlPolicy declaredNoRestriction() {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), true);
    }

    private static UsageControlPolicy quotaPolicy(final int maxCount) {
        return new UsageControlPolicy(
                UsageControlPolicy.Element.ofCount(true, maxCount),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), false);
    }

    private static UsageControlPolicy purposePolicy(final String text) {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.ofText(true, text),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), false);
    }

    private static UsageControlPolicy quotaAndPurposePolicy(final int maxCount,
            final String purposeText) {
        return new UsageControlPolicy(UsageControlPolicy.Element.ofCount(true, maxCount),
                UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofText(true, purposeText),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), false);
    }

    private static UsageControlPolicy noRedistributionPolicy() {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(true), false);
    }

    private static UsageControlPolicy termPolicy(final String start, final String end) {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofTerm(true, start, end),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), false);
    }
}
