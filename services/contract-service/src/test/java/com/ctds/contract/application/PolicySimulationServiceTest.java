package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.infrastructure.UsageCounterStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 模拟判定矩阵单测（WBS-3.4.6 hifi §2 / §5 / §9①）：五要素 × 双向 + 边界（假想计数
 * limit−1/limit/limit+1、假想日期 首日/末日/前后一日、草稿试算合法/非法、空策略与显式无限制、
 * 假想上下文缺省填充、非生效合约策略失效）。判定语义与真实执行腿同源（同一 {@code PolicyJudge}
 * + 同一配额判据），模拟通道零副作用（不触计数、不写记录）。
 */
@ExtendWith(MockitoExtension.class)
class PolicySimulationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_NO = "CO000101";
    private static final String REQ = "S-req";
    private static final String C0008 = "1008C0008";
    private static final String C0015 = "1008C0015";
    private static final int LIMIT = 100;
    private static final LocalDate TODAY = LocalDate.of(2027, 6, 15);
    private static final LocalDate START = LocalDate.of(2027, 6, 1);
    private static final LocalDate END = LocalDate.of(2027, 12, 31);
    private static final String PURPOSE = "风控建模";
    private static final String TERRITORY = "本市域";
    private static final LocalDateTime EFFECTIVE_AT = LocalDateTime.of(2027, 5, 20, 10, 0);
    private static final Clock FIXED = Clock.fixed(
            TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock private ContractRepository repository;
    @Mock private ContractQueryService contractQueryService;
    @Mock private UsageCounterStore counterStore;
    @Mock private PolicyExecutionService policyExecutionService;

    private PolicySimulationService service;

    @BeforeEach
    void setUp() {
        service = new PolicySimulationService(new ContractVisibilityGuard(repository, FIXED),
                contractQueryService, counterStore, policyExecutionService, FIXED);
    }

    @Test
    void simulationFiveElementsBidirectionalMatrix() {
        stubEffective(allElementsPolicy(LIMIT));
        // 合规放行腿（基准上下文：计数 0 + 注入 Clock 当天 + 约定用途/域 + USE）
        final PolicySimulationService.SimulationOutcome allowed = simulate("USE", PURPOSE, TERRITORY, 0, null);
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.violations()).isEmpty();
        assertThat(allowed.strategySource()).isEqualTo("EFFECTIVE");
        assertThat(allowed.strategyEffective()).isTrue();
        assertThat(allowed.contractStatus()).isEqualTo(ContractStatus.EFFECTIVE.name());
        assertThat(allowed.assumedUsedCount()).isZero();
        assertThat(allowed.assumedDate()).isEqualTo(TODAY);
        assertThat(allowed.policySnapshot()).containsOnlyKeys("usage.quota", "usage.term",
                "usage.purpose", "usage.territory", "usage.no_redistribution");
        // 五要素越界腿（逐要素触发；配额仅在四要素全过时判定）
        assertThat(simulate("USE", PURPOSE, TERRITORY, 0, END.plusDays(1)).violations())
                .containsExactly("TERM_EXPIRED");
        assertThat(simulate("USE", PURPOSE + "-越界", TERRITORY, 0, null).violations())
                .containsExactly("PURPOSE_MISMATCH");
        assertThat(simulate("USE", PURPOSE, TERRITORY + "-越界", 0, null).violations())
                .containsExactly("TERRITORY_MISMATCH");
        assertThat(simulate("REDISTRIBUTE", PURPOSE, TERRITORY, 0, null).violations())
                .containsExactly("REDISTRIBUTION_FORBIDDEN");
        assertThat(simulate("USE", PURPOSE, TERRITORY, LIMIT, null).violations())
                .containsExactly("QUOTA_EXHAUSTED");
        // 多要素同时触发 → 全查明细（无短路；配额不参与）
        final PolicySimulationService.SimulationOutcome multiple =
                simulate("REDISTRIBUTE", PURPOSE + "-越界", TERRITORY + "-越界", LIMIT, null);
        assertThat(multiple.allowed()).isFalse();
        assertThat(multiple.violations()).containsExactly("PURPOSE_MISMATCH",
                "TERRITORY_MISMATCH", "REDISTRIBUTION_FORBIDDEN");
    }

    @Test
    void simulationQuotaBoundaryAtLimitMinusOneLimitAndLimitPlusOne() {
        stubEffective(quotaPolicy(LIMIT));
        assertThat(simulate("USE", null, null, LIMIT - 1, null).allowed()).isTrue();
        assertThat(simulate("USE", null, null, LIMIT, null).violations())
                .containsExactly("QUOTA_EXHAUSTED");
        assertThat(simulate("USE", null, null, LIMIT + 1, null).violations())
                .containsExactly("QUOTA_EXHAUSTED");
    }

    @Test
    void simulationTermBoundaryStartDayEndDayAndBeyond() {
        stubEffective(termPolicy(START, END));
        assertThat(simulate("USE", null, null, 0, START).allowed()).isTrue();
        assertThat(simulate("USE", null, null, 0, END).allowed()).isTrue();
        assertThat(simulate("USE", null, null, 0, START.minusDays(1)).violations())
                .containsExactly("TERM_EXPIRED");
        assertThat(simulate("USE", null, null, 0, END.plusDays(1)).violations())
                .containsExactly("TERM_EXPIRED");
    }

    @Test
    void simulationPurposeAndTerritoryExactEquality() {
        stubEffective(purposeAndTerritoryPolicy());
        assertThat(simulate("USE", PURPOSE, TERRITORY, 0, null).allowed()).isTrue();
        assertThat(simulate("USE", " " + PURPOSE + " ", " " + TERRITORY + " ", 0, null).allowed())
                .as("请求侧 trim 对称口径（策略侧文本已由解析器规范化）").isTrue();
        assertThat(simulate("USE", PURPOSE + "-越界", TERRITORY, 0, null).violations())
                .containsExactly("PURPOSE_MISMATCH");
        assertThat(simulate("USE", PURPOSE, TERRITORY + "-越界", 0, null).violations())
                .containsExactly("TERRITORY_MISMATCH");
    }

    @Test
    void simulationDraftStrategyValidAndInvalidRejectedC0015() throws Exception {
        stubEffective(quotaPolicy(LIMIT));
        // 合法草稿（仅用途要素启用）：判定按草稿而非生效策略 → 合规放行 + 快照仅含草稿启用要素
        final PolicySimulationService.SimulationOutcome draft = simulateDocument(MAPPER.readTree(
                "{\"purpose\":{\"enabled\":true,\"text\":\"" + PURPOSE + "\"},"
                        + "\"noRestrictionDeclared\":false}"), PURPOSE);
        assertThat(draft.strategySource()).isEqualTo("DRAFT");
        assertThat(draft.allowed()).isTrue();
        assertThat(draft.policySnapshot()).containsOnlyKeys("usage.purpose");
        // 非法草稿（quota.maxCount 违反 "≥ 1"）→ 1008C0015（解析器单点结论，不落库）
        assertThat(jsonCodeOf(MAPPER.readTree(
                "{\"quota\":{\"enabled\":true,\"maxCount\":0},\"noRestrictionDeclared\":false}")))
                .isEqualTo(C0015);
    }

    @Test
    void simulationEmptyOrExplicitNoRestrictionAllowed() {
        stubEffective(UsageControlPolicy.empty());
        final PolicySimulationService.SimulationOutcome blank = simulate("USE", null, null, 0, null);
        assertThat(blank.allowed()).isTrue();
        assertThat(blank.violations()).isEmpty();
        assertThat(blank.policySnapshot()).isEmpty();
        stubEffective(noRestrictionPolicy());
        final PolicySimulationService.SimulationOutcome declared = simulate("USE", null, null, 0, null);
        assertThat(declared.allowed()).isTrue();
        assertThat(declared.violations()).isEmpty();
        assertThat(declared.policySnapshot()).isEmpty();
    }

    @Test
    void simulationAssumedContextDefaultsToRealCountAndToday() {
        stubEffective(quotaPolicy(LIMIT));
        given(counterStore.currentCount(CONTRACT_NO)).willReturn(7);
        final PolicySimulationService.SimulationOutcome outcome = simulate("USE", null, null, null, null);
        assertThat(outcome.assumedUsedCount()).as("假想计数缺省 = 真实计数").isEqualTo(7);
        assertThat(outcome.assumedDate()).as("假想日期缺省 = 注入 Clock 当天").isEqualTo(TODAY);
        assertThat(outcome.allowed()).isTrue();
    }

    @Test
    void simulationAssumedCountOutOfRangeRejectedC0008() {
        stubContract(ContractStatus.EFFECTIVE);
        assertThat(codeOf(-1, null, "USE")).isEqualTo(C0008);
        assertThat(codeOf(1_000_001, null, "USE")).isEqualTo(C0008);
        assertThat(codeOf(0, "2027/06/15", "USE")).isEqualTo(C0008);
        assertThat(codeOf(0, "2027-13-01", "USE")).isEqualTo(C0008);
        assertThat(codeOf(0, null, "FORWARD")).isEqualTo(C0008);
        assertThat(codeOf(0, null, null)).isEqualTo(C0008);
    }

    @Test
    void simulationNonEffectiveContractReportsStrategyIneffective() {
        stubContract(ContractStatus.TERMINATED);
        given(contractQueryService.loadEffectiveStrategy(CONTRACT_NO)).willReturn(
                new ContractQueryService.ContractStrategySnapshot(CONTRACT_NO,
                        ContractStatus.TERMINATED.name(), EFFECTIVE_AT, null));
        final PolicySimulationService.SimulationOutcome outcome =
                simulate("USE", PURPOSE, TERRITORY, 0, null);
        assertThat(outcome.strategyEffective()).as("合约非生效态 → 策略无效（S3-7 联动）").isFalse();
        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.policySnapshot()).isNull();
        assertThat(outcome.contractStatus()).isEqualTo(ContractStatus.TERMINATED.name());
    }

    // ==== 夹具与助手 ====

    private void stubContract(final ContractStatus status) {
        given(repository.findByNo(CONTRACT_NO)).willReturn(Optional.of(contract(status)));
    }

    private void stubEffective(final UsageControlPolicy policy) {
        stubContract(ContractStatus.EFFECTIVE);
        given(contractQueryService.loadEffectiveStrategy(CONTRACT_NO)).willReturn(
                new ContractQueryService.ContractStrategySnapshot(CONTRACT_NO,
                        ContractStatus.EFFECTIVE.name(), EFFECTIVE_AT, policy));
    }

    private PolicySimulationService.SimulationOutcome simulate(final String actionType,
            final String purpose, final String territory, final Integer assumedUsedCount,
            final LocalDate assumedDate) {
        return service.simulate(CONTRACT_NO, new PolicySimulationService.SimulationCommand(
                actionType, purpose, territory, assumedUsedCount,
                assumedDate == null ? null : assumedDate.toString(), null), REQ);
    }

    private PolicySimulationService.SimulationOutcome simulateDocument(final JsonNode document,
            final String purpose) {
        return service.simulate(CONTRACT_NO, new PolicySimulationService.SimulationCommand("USE",
                purpose, null, 0, null, document), REQ);
    }

    private String jsonCodeOf(final JsonNode document) {
        return ((ContractBizException) catchThrowable(() -> simulateDocument(document, PURPOSE)))
                .getErrorCode().value();
    }

    private String codeOf(final Integer assumedUsedCount, final String assumedDate,
            final String actionType) {
        return ((ContractBizException) catchThrowable(
                () -> service.simulate(CONTRACT_NO, new PolicySimulationService.SimulationCommand(
                        actionType, null, null, assumedUsedCount, assumedDate, null), REQ)))
                .getErrorCode().value();
    }

    private static Contract contract(final ContractStatus status) {
        return new Contract(1L, CONTRACT_NO, 12L, "城市餐饮单位经营数据集", "S-prov", REQ, "CT000001",
                1, "PER_CALL", new BigDecimal("1.50"), 1, status, null, null, null, EFFECTIVE_AT,
                null, null, null, REQ, EFFECTIVE_AT, EFFECTIVE_AT);
    }

    private static UsageControlPolicy quotaPolicy(final int maxCount) {
        return new UsageControlPolicy(UsageControlPolicy.Element.ofCount(true, maxCount),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                false);
    }

    private static UsageControlPolicy termPolicy(final LocalDate start, final LocalDate end) {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.ofTerm(true, start.toString(), end.toString()),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), false);
    }

    private static UsageControlPolicy purposeAndTerritoryPolicy() {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.ofText(true, PURPOSE),
                UsageControlPolicy.Element.ofText(true, TERRITORY),
                UsageControlPolicy.Element.flag(false), false);
    }

    private static UsageControlPolicy allElementsPolicy(final int maxCount) {
        return new UsageControlPolicy(UsageControlPolicy.Element.ofCount(true, maxCount),
                UsageControlPolicy.Element.ofTerm(true, START.toString(), END.toString()),
                UsageControlPolicy.Element.ofText(true, PURPOSE),
                UsageControlPolicy.Element.ofText(true, TERRITORY),
                UsageControlPolicy.Element.flag(true), false);
    }

    private static UsageControlPolicy noRestrictionPolicy() {
        return new UsageControlPolicy(UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false),
                UsageControlPolicy.Element.flag(false), UsageControlPolicy.Element.flag(false), true);
    }
}
