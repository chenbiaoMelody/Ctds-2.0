package com.ctds.contract.interfaces.dto;

import com.ctds.contract.application.PolicySimulationService;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 策略模拟器与测试台出/入站视图（WBS-3.4.6 hifi §2~§4）：模拟试算、测试台报告、受控执行三端点
 * 的请求与响应载体（单文件承载——沿 hifi §1 契约；出站字段集显式锚定，无数据本体）。
 *
 * <p>请求侧入参白名单由结构锚测试钉死（无任何"跳过判定/强制放行"字段——服务端强制，规格行为 5
 * 规则 3）；出站不含请求原文（留痕四要素纪律，模拟与测试台本就不落任何文本）。</p>
 */
public final class SimulationViews {

    private SimulationViews() {
    }

    /** 模拟试算请求（假想上下文 + 可选策略草稿；白名单见结构锚 `endpointsExposeNoBypassParameter`）。 */
    public record SimulationRequest(String actionType, String purpose, String territory,
            Integer assumedUsedCount, String assumedDate, JsonNode strategyDocument) {
    }

    /** 受控执行请求（发起方 = 认证主体，不接受"以他人身份发起"参数）。 */
    public record ExecutionRequest(String actionType, String purpose, String territory) {
    }

    /** 模拟结论 → 出站视图。 */
    public static Simulation of(final PolicySimulationService.SimulationOutcome outcome) {
        return new Simulation(outcome.contractNo(), outcome.contractStatus(), outcome.strategySource(),
                outcome.strategyEffective(), outcome.allowed(), outcome.violations(),
                outcome.assumedUsedCount(), outcome.assumedDate(), outcome.policySnapshot());
    }

    /** 模拟试算响应（HTTP 恒 200——判定为拒绝时以 `allowed=false` 数据主体返回，非异常）。 */
    public record Simulation(String contractNo, String contractStatus, String strategySource,
            boolean strategyEffective, boolean allowed, List<String> violations,
            int assumedUsedCount, LocalDate assumedDate, Map<String, String> policySnapshot) {
    }

    /** 测试台报告 → 出站视图。 */
    public static TestbenchReport of(final PolicySimulationService.TestbenchOutcome outcome) {
        final List<TestbenchReport.Scenario> scenarios = outcome.scenarios().stream()
                .map(row -> new TestbenchReport.Scenario(row.code(), row.elementKey(), row.direction(),
                        row.expectation(), row.outcome(),
                        new TestbenchReport.Actual(row.actualAllowed(), row.actualViolations()),
                        row.note()))
                .toList();
        return new TestbenchReport(outcome.contractNo(), outcome.contractStatus(),
                outcome.strategyEffective(), outcome.runAt(), scenarios,
                new TestbenchReport.Summary(outcome.summary().total(), outcome.summary().pass(),
                        outcome.summary().fail(), outcome.summary().skipped()));
    }

    /** 测试台报告（零落库——实时返回）。 */
    public record TestbenchReport(String contractNo, String contractStatus, boolean strategyEffective,
            LocalDateTime runAt, List<Scenario> scenarios, Summary summary) {

        /** 逐条场景结论（`outcome` = PASS / FAIL / SKIPPED）。 */
        public record Scenario(String code, String elementKey, String direction, String expectation,
                String outcome, Actual actual, String note) {
        }

        /** 场景实际结论（走模拟通道所得——不做判定跳过的旁路）。 */
        public record Actual(boolean allowed, List<String> violations) {
        }

        /** 报告汇总（`skipped` = 要素未启用数——诚实不假绿）。 */
        public record Summary(int total, int pass, int fail, int skipped) {
        }
    }

    /** 受控执行结论 → 出站视图（放行腿；拒绝腿走 1008C0020 / C0013 / C0012 异常出口）。 */
    public static Execution of(final String contractNo, final UsageVerdict verdict) {
        return new Execution(contractNo, verdict.allowed(), verdict.usedCount(),
                verdict.occurredAt());
    }

    /** 受控执行响应（放行：`usedCount` = 递增后真实值）。 */
    public record Execution(String contractNo, boolean allowed, int usedCount,
            LocalDateTime occurredAt) {
    }
}
