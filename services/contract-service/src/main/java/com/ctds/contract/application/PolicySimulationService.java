package com.ctds.contract.application;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.PolicyElementCatalog;
import com.ctds.contract.domain.policy.PolicyJudge;
import com.ctds.contract.domain.policy.PolicyViolation;
import com.ctds.contract.domain.policy.SimulationContext;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsagePolicyDsl;
import com.ctds.contract.domain.policy.UsagePolicyDslParser;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.ctds.contract.infrastructure.UsageCounterStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 策略模拟器与测试台编排（WBS-3.4.6 hifi §1~§5）：承载模拟器三端点的应用层编排——模拟试算
 * （只读零副作用）/ 测试台（只读零副作用）/ 受控执行（真实执行通道，委托 {@code check}）。
 *
 * <p>三条通道的判定语义同源（同一 {@code PolicyJudge} + 同一要素目录 + 同一配额判据），差异只在
 * 副作用与错误表达（ADR-021 §2.2）：模拟与测试台<b>不写计数、不写记录、不落库</b>；受控执行走
 * 3.4.5 既有链路（计数递增、放行记录、拒绝留痕）——<b>零改动</b>。</p>
 *
 * <p>编排次序（校验前置，不触策略读取）：① 可见性守卫（模拟/测试台 = 参与方 + 治理；受控执行 =
 * 仅参与方）；② 入参校验（动作类型/假想计数/日期 → 1008C0008）；③ 策略来源（草稿经解析器单点
 * 校验 → 1008C0015；缺省 = QC1 生效策略）；④ 状态门槛（合约非生效 → 以数据表达策略失效，不抛
 * 异常）；⑤ 四要素全查 → 配额纯判定（顺序镜像 ADR-020 §2.2 引擎判定表，判定语义消费不复制）。</p>
 *
 * <p><b>事务口径</b>（ADR-021 §2.6）：三方法<b>均不加 {@code @Transactional}</b>——可见性守卫的
 * {@code DENIED_ACCESS} 留痕是拒绝路径的一部分，必须可写（{@code readOnly = true} 连接写库实测
 * {@code TransientDataAccessResourceException: Connection is read-only}）且必须超越拒绝异常存活
 * （可写事务在异常回滚时会把留痕一并吞掉）；沿 R12 `UsageQueryService.summary` 既有口径
 * （无事务标注、留痕走自提交）。模拟与测试台的<b>零副作用</b>由结构性保证——不调用
 * {@code UsageCounterStore.tryIncrement}、不写 {@code contract_usage_log}（渲染结论与报告全部
 * 在内存完成）。</p>
 */
@Service
public class PolicySimulationService {

    /** 策略来源：合约当前生效策略（QC1）。 */
    private static final String SOURCE_EFFECTIVE = "EFFECTIVE";

    /** 策略来源：调用方提交的策略草稿。 */
    private static final String SOURCE_DRAFT = "DRAFT";

    /** 场景结论：实际与期望一致。 */
    private static final String OUTCOME_PASS = "PASS";

    /** 场景结论：实际与期望不一致。 */
    private static final String OUTCOME_FAIL = "FAIL";

    /** 场景结论：要素未启用（诚实不假绿——既不判通过也不判失败）。 */
    private static final String OUTCOME_SKIPPED = "SKIPPED";

    /** 合约非生效态的全场景期望文案（S3-7 联动——策略随合约同步失效）。 */
    static final String EXPECTATION_INEFFECTIVE = "拒绝（策略失效）";

    private final ContractVisibilityGuard visibilityGuard;
    private final ContractQueryService contractQueryService;
    private final UsageCounterStore counterStore;
    private final PolicyExecutionService policyExecutionService;
    private final Clock clock;

    public PolicySimulationService(final ContractVisibilityGuard visibilityGuard,
            final ContractQueryService contractQueryService, final UsageCounterStore counterStore,
            final PolicyExecutionService policyExecutionService, final Clock clock) {
        this.visibilityGuard = visibilityGuard;
        this.contractQueryService = contractQueryService;
        this.counterStore = counterStore;
        this.policyExecutionService = policyExecutionService;
        this.clock = clock;
    }

    /**
     * 模拟试算（hifi §2）：假想上下文 + 零副作用 + 结论以数据返回（拒绝亦 HTTP 200 +
     * {@code allowed=false}，与执行通道的异常语义分离）。
     *
     * @throws ContractBizException 1008C0012 合约不存在/不可见（防枚举同码同文）；1008C0008
     *         入参非法；1008C0015 策略草稿校验不过
     */
    public SimulationOutcome simulate(final String contractNo, final SimulationCommand command,
            final String operatorNo) {
        // 步 1 合约定位 + 可见性（参与方 + 治理；不存在/不可见 → 同码同文防枚举 + 留痕）
        visibilityGuard.requireReadable(contractNo, operatorNo);
        // 步 2 入参校验（判定前不触策略读取——C0008）
        final UsageActionType actionType = parseActionType(command.actionType());
        validateAssumedUsedCount(command.assumedUsedCount());
        final LocalDate assumedDate = parseAssumedDate(command.assumedDate());
        // 步 3 策略来源（草稿单点校验 → C0015；缺省 = QC1 生效策略）
        final ContractQueryService.ContractStrategySnapshot snapshot =
                contractQueryService.loadEffectiveStrategy(contractNo);
        final boolean draftProvided = command.strategyDocument() != null
                && !command.strategyDocument().isNull();
        final String strategySource = draftProvided ? SOURCE_DRAFT : SOURCE_EFFECTIVE;
        final UsageControlPolicy strategy = draftProvided
                ? parseDraft(command.strategyDocument()) : snapshot.strategy();
        final SimulationContext context = SimulationContext.of(command.assumedUsedCount(), assumedDate,
                command.assumedUsedCount() == null ? counterStore.currentCount(contractNo) : 0,
                LocalDate.now(clock));
        // 步 4 状态门槛（合约非生效 → 策略失效以数据表达：allowed=false、violations 空、快照 null）
        if (!ContractStatus.EFFECTIVE.name().equals(snapshot.status())) {
            return new SimulationOutcome(contractNo, snapshot.status(), strategySource, false, false,
                    List.of(), context.assumedUsedCount(), context.assumedDate(), null);
        }
        // 步 5 判定（四要素全查 → 配额纯判定；空策略/显式无限制放行不计数）
        final SimulationVerdict verdict = judge(strategy, actionType, command.purpose(),
                command.territory(), context, operatorNo);
        return new SimulationOutcome(contractNo, snapshot.status(), strategySource, true,
                verdict.allowed(), names(verdict.violations()), context.assumedUsedCount(),
                context.assumedDate(), snapshotOf(strategy));
    }

    /**
     * 测试台（hifi §3）：目录驱动 11 场景逐条走模拟通道 → 结构化报告（零落库；报告非业务事件）。
     *
     * <p>三态判定（诚实不假绿）：要素未启用 → {@code SKIPPED}；合约非生效态 → 全场景期望
     * "拒绝（策略失效）"；生效且适用 → 按期望与实际（放行/拒绝 + 触发要素集合）比对。</p>
     *
     * @throws ContractBizException 1008C0012 合约不存在/不可见（防枚举同码同文）
     */
    public TestbenchOutcome runTestbench(final String contractNo, final String operatorNo) {
        visibilityGuard.requireReadable(contractNo, operatorNo);
        final ContractQueryService.ContractStrategySnapshot snapshot =
                contractQueryService.loadEffectiveStrategy(contractNo);
        final LocalDate today = LocalDate.now(clock);
        final boolean strategyEffective = ContractStatus.EFFECTIVE.name().equals(snapshot.status());
        final List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (final PolicyTestbenchScenarios.PlannedScenario plan
                : PolicyTestbenchScenarios.plan(snapshot.strategy(), today)) {
            outcomes.add(evaluate(snapshot, strategyEffective, plan, operatorNo, today));
        }
        return new TestbenchOutcome(contractNo, snapshot.status(), strategyEffective,
                LocalDateTime.now(clock), List.copyOf(outcomes), summarize(outcomes));
    }

    /**
     * 受控执行（hifi §4）：仅参与方可见性守卫 → 3.4.5 {@code check}（零改动：计数递增、放行记录、
     * 拒绝留痕、配额判检一体原子递增全部沿用既有链路）。
     *
     * @throws ContractBizException 1008C0012 合约不存在/非参与方（同码同文防枚举）；1008C0013
     *         合约状态失效；1008C0020 越界使用；1008C0008 参数非法
     */
    public UsageVerdict executeUsage(final ExecutionCommand command, final String operatorNo) {
        visibilityGuard.requireParticipant(command.contractNo(), operatorNo);
        final UsageActionType actionType = parseActionType(command.actionType());
        return policyExecutionService.check(command.contractNo(),
                new UsageRequest(operatorNo, actionType, command.purpose(), command.territory()));
    }

    // ==== 场景评估（模拟通道共用判定镜像——与 simulate 同源，零副作用）====

    private ScenarioOutcome evaluate(final ContractQueryService.ContractStrategySnapshot snapshot,
            final boolean strategyEffective, final PolicyTestbenchScenarios.PlannedScenario plan,
            final String operatorNo, final LocalDate today) {
        if (strategyEffective && !plan.applicable()) {
            // 要素未启用 / 场景不适用 → SKIPPED（基准上下文不参与期望判定）
            return new ScenarioOutcome(plan.code(), plan.elementKey(), plan.direction().name(),
                    PolicyTestbenchScenarios.EXPECTATION_DISABLED, OUTCOME_SKIPPED, false, List.of(),
                    null);
        }
        final String expectation = strategyEffective ? plan.expectation() : EXPECTATION_INEFFECTIVE;
        final SimulationContext context = new SimulationContext(plan.assumedUsedCount(),
                plan.assumedDate());
        final SimulationVerdict verdict = strategyEffective
                ? judge(snapshot.strategy(), plan.actionType(), plan.purpose(), plan.territory(),
                        context, operatorNo)
                : new SimulationVerdict(false, List.of());
        final boolean pass = matches(plan, strategyEffective, verdict);
        return new ScenarioOutcome(plan.code(), plan.elementKey(), plan.direction().name(), expectation,
                pass ? OUTCOME_PASS : OUTCOME_FAIL, verdict.allowed(), names(verdict.violations()),
                pass ? null : describe(expectation, verdict));
    }

    /** 期望与实际比对：非生效 → 实际必须拒绝；ALLOW → 放行且零触发；DENY → 触发要素集合恰为期望。 */
    private static boolean matches(final PolicyTestbenchScenarios.PlannedScenario plan,
            final boolean strategyEffective, final SimulationVerdict verdict) {
        if (!strategyEffective) {
            return !verdict.allowed();
        }
        if (plan.direction() == PolicyTestbenchScenarios.Direction.ALLOW) {
            return verdict.allowed() && verdict.violations().isEmpty();
        }
        return !verdict.allowed() && plan.expectedViolation() != null
                && Set.copyOf(verdict.violations()).equals(Set.of(plan.expectedViolation()));
    }

    /** 不一致说明（报告 note；不含请求文本原文——留痕四要素纪律）。 */
    private static String describe(final String expectation, final SimulationVerdict verdict) {
        return "期望=" + expectation + "；实际="
                + (verdict.allowed() ? "放行" : String.join(",", names(verdict.violations())));
    }

    private static TestbenchSummary summarize(final List<ScenarioOutcome> outcomes) {
        int pass = 0;
        int fail = 0;
        int skipped = 0;
        for (final ScenarioOutcome outcome : outcomes) {
            switch (outcome.outcome()) {
                case OUTCOME_PASS -> pass++;
                case OUTCOME_FAIL -> fail++;
                default -> skipped++;
            }
        }
        return new TestbenchSummary(outcomes.size(), pass, fail, skipped);
    }

    // ==== 判定镜像（hifi §2 判定顺序；判定语义唯一权威 = PolicyJudge / PolicyElementCatalog）====

    private SimulationVerdict judge(final UsageControlPolicy strategy, final UsageActionType actionType,
            final String purpose, final String territory, final SimulationContext context,
            final String requesterNo) {
        // 空策略 / 显式无限制 → 放行不计数（与引擎步 3 同口径）
        if (strategy == null || strategy.noRestrictionDeclared()) {
            return new SimulationVerdict(true, List.of());
        }
        // 四要素全查（有触发 → 拒绝 + 全查明细，不再判配额——与引擎步 8 一致）
        final List<PolicyViolation> violations = PolicyJudge.judge(strategy,
                new UsageRequest(requesterNo, actionType, purpose, territory), context.assumedDate());
        if (!violations.isEmpty()) {
            return new SimulationVerdict(false, violations);
        }
        // 四要素全过 → 配额纯判定（与真实执行腿判检一体原子递增同源）
        final List<PolicyViolation> quota = PolicyJudge.judgeQuota(strategy,
                context.assumedUsedCount());
        return new SimulationVerdict(quota.isEmpty(), quota);
    }

    /** 参与判定的要素取值快照（仅启用项；目录键声明序——键 → 取值文本）。 */
    private static Map<String, String> snapshotOf(final UsageControlPolicy strategy) {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (final String key : PolicyElementCatalog.keys()) {
            final PolicyElementCatalog.ElementDefinition definition = PolicyElementCatalog.find(key)
                    .orElseThrow();
            final UsageControlPolicy.Element element =
                    PolicyTestbenchScenarios.elementOf(strategy, definition.field());
            if (element != null && element.enabled()) {
                snapshot.put(key, valueOf(definition.field(), element));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static String valueOf(final String field, final UsageControlPolicy.Element element) {
        return switch (field) {
            case UsagePolicyDsl.FIELD_QUOTA -> String.valueOf(element.maxCount());
            case UsagePolicyDsl.FIELD_TERM -> element.startDate() + "~" + element.endDate();
            case UsagePolicyDsl.FIELD_NO_REDISTRIBUTION -> "true";
            default -> element.text();
        };
    }

    // ==== 入参校验（hifi §7：一律 1008C0008，单一常量文案）====

    private static UsageActionType parseActionType(final String actionType) {
        if (actionType == null) {
            throw paramInvalid();
        }
        try {
            return UsageActionType.valueOf(actionType);
        } catch (final IllegalArgumentException ex) {
            throw paramInvalid();
        }
    }

    private static void validateAssumedUsedCount(final Integer assumedUsedCount) {
        if (assumedUsedCount != null && (assumedUsedCount < 0
                || assumedUsedCount > SimulationContext.MAX_ASSUMED_USED_COUNT)) {
            throw paramInvalid();
        }
    }

    private static LocalDate parseAssumedDate(final String assumedDate) {
        if (assumedDate == null) {
            return null;
        }
        try {
            return LocalDate.parse(assumedDate);
        } catch (final DateTimeParseException ex) {
            throw paramInvalid();
        }
    }

    /** 策略草稿单点校验（解析器结论；非法 → 1008C0015，不落库）。 */
    private UsageControlPolicy parseDraft(final JsonNode document) {
        final UsagePolicyDslParser.ParseResult parsed =
                UsagePolicyDslParser.parse(document, LocalDate.now(clock));
        if (!parsed.isValid()) {
            throw new ContractBizException(ContractErrorCodes.POLICY_CLAUSE_INVALID,
                    ContractErrorCodes.POLICY_CLAUSE_INVALID_MESSAGE);
        }
        return parsed.policy();
    }

    private static ContractBizException paramInvalid() {
        return new ContractBizException(ContractErrorCodes.TEMPLATE_PARAM_INVALID,
                ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
    }

    private static List<String> names(final List<PolicyViolation> violations) {
        final List<String> names = new ArrayList<>(violations.size());
        for (final PolicyViolation violation : violations) {
            names.add(violation.name());
        }
        return List.copyOf(names);
    }

    /** 判定结论载体（内部；不复制要素语义——violations 为 PolicyJudge 结论）。 */
    private record SimulationVerdict(boolean allowed, List<PolicyViolation> violations) {
    }

    /**
     * 模拟试算入参（白名单——无任何"跳过判定/强制放行"字段）。
     *
     * @param actionType        动作类型文本（USE / REDISTRIBUTE；非法 → 1008C0008）
     * @param purpose           本次声明用途（可空）
     * @param territory         本次声明域（可空）
     * @param assumedUsedCount  假想已用次数（可空 = 真实计数；越界 → 1008C0008）
     * @param assumedDate       假想判定日期（可空 = 注入 Clock 当天；格式非法 → 1008C0008）
     * @param strategyDocument  策略草稿（可空 = 合约当前生效策略；非法 → 1008C0015）
     */
    public record SimulationCommand(String actionType, String purpose, String territory,
            Integer assumedUsedCount, String assumedDate, JsonNode strategyDocument) {
    }

    /**
     * 受控执行入参（发起方 = 认证主体，不接受"以他人身份发起"参数）。
     *
     * @param contractNo 合约编号
     * @param actionType 动作类型文本（USE / REDISTRIBUTE；非法 → 1008C0008）
     * @param purpose    本次声明用途（可空）
     * @param territory  本次声明域（可空）
     */
    public record ExecutionCommand(String contractNo, String actionType, String purpose,
            String territory) {
    }

    /**
     * 模拟结论（模拟试算出站装配用）。
     *
     * @param contractNo        合约编号
     * @param contractStatus    合约状态名
     * @param strategySource    EFFECTIVE / DRAFT
     * @param strategyEffective 策略是否有效（false = 合约非生效态 → 结论恒拒绝、快照为 null）
     * @param allowed           模拟判定结论
     * @param violations        触发要素枚举名（全查明细）
     * @param assumedUsedCount  本次实际使用的假想计数（回显）
     * @param assumedDate       本次实际使用的假想日期（回显）
     * @param policySnapshot    参与判定的要素取值快照（仅启用项；策略失效为 null）
     */
    public record SimulationOutcome(String contractNo, String contractStatus, String strategySource,
            boolean strategyEffective, boolean allowed, List<String> violations,
            int assumedUsedCount, LocalDate assumedDate, Map<String, String> policySnapshot) {
    }

    /**
     * 测试台报告（报告不落库——自检动作不是业务使用事件，移交-3 登记归档需求）。
     *
     * @param contractNo        合约编号
     * @param contractStatus    合约状态名
     * @param strategyEffective 策略是否有效
     * @param runAt             执行时点（注入 Clock）
     * @param scenarios         逐条场景结论
     * @param summary           汇总
     */
    public record TestbenchOutcome(String contractNo, String contractStatus,
            boolean strategyEffective, LocalDateTime runAt, List<ScenarioOutcome> scenarios,
            TestbenchSummary summary) {
    }

    /**
     * 逐条场景结论。
     *
     * @param code             场景码
     * @param elementKey       目录键（空策略场景为 null）
     * @param direction        ALLOW / DENY
     * @param expectation      期望文案
     * @param outcome          PASS / FAIL / SKIPPED
     * @param actualAllowed    实际结论（SKIPPED 时为基准上下文结论，仅作留痕）
     * @param actualViolations 实际触发要素枚举名
     * @param note             说明（跳过原因 / 不一致说明；无则 null）
     */
    public record ScenarioOutcome(String code, String elementKey, String direction,
            String expectation, String outcome, boolean actualAllowed,
            List<String> actualViolations, String note) {
    }

    /**
     * 报告汇总。
     *
     * @param total   场景总数
     * @param pass    通过数
     * @param fail    未通过数
     * @param skipped 跳过数（要素未启用——诚实不假绿）
     */
    public record TestbenchSummary(int total, int pass, int fail, int skipped) {
    }
}
