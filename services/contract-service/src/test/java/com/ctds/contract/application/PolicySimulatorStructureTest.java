package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.PolicyElementCatalog;
import com.ctds.contract.domain.policy.PolicyJudge;
import com.ctds.contract.domain.policy.SimulationContext;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.interfaces.ContractPolicySimulationController;
import com.ctds.contract.interfaces.ContractUsageExecutionController;
import com.ctds.contract.interfaces.dto.SimulationViews;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟器结构锚（WBS-3.4.6 任务卡 §三"结构锚"两行 / hifi §3 / §9④）：
 *
 * <p>① 场景集与要素目录<b>同源</b>（承接 3.4.4 移交-2）：场景集按目录生成，目录扩要素而场景集
 * 未同步 → 计划阶段 fail-fast，本锚红；② 三端点<b>无任何"跳过判定/强制放行"入参</b>（fail-closed，
 * 规格行为 5 规则 3）：请求载体入参白名单全等断言 + 端点参数注解白名单；③ 配额纯判定新增于既有
 * 判定位（{@code PolicyJudge} 只增不改、纯函数无实例状态）。</p>
 */
class PolicySimulatorStructureTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 6, 15);

    @Test
    void testbenchScenariosCoverEveryCatalogElement() {
        final List<PolicyTestbenchScenarios.PlannedScenario> plans =
                PolicyTestbenchScenarios.plan(allElementsPolicy(), TODAY);
        // 场景表逐条钉死（hifi §3：目录五要素声明序 × 双向 + 空策略一条；再分发要素先 DENY 后 ALLOW）
        assertThat(plans).extracting(plan -> plan.code() + " " + plan.elementKey() + " "
                        + plan.direction())
                .containsExactly("U1 usage.quota ALLOW", "U2 usage.quota DENY",
                        "U3 usage.term ALLOW", "U4 usage.term DENY", "U5 usage.purpose ALLOW",
                        "U6 usage.purpose DENY", "U7 usage.territory ALLOW",
                        "U8 usage.territory DENY", "U9 usage.no_redistribution DENY",
                        "U10 usage.no_redistribution ALLOW", "U11 null ALLOW");
        // 同源锚：目录中每个要素都有 ALLOW / DENY 两条场景（目录扩要素漏配 → 本断言红）
        final Map<String, List<PolicyTestbenchScenarios.PlannedScenario>> byElement = plans.stream()
                .filter(plan -> plan.elementKey() != null)
                .collect(Collectors.groupingBy(PolicyTestbenchScenarios.PlannedScenario::elementKey));
        assertThat(byElement.keySet()).as("场景集与要素目录同源").isEqualTo(PolicyElementCatalog.keys());
        for (final String key : PolicyElementCatalog.keys()) {
            assertThat(byElement.get(key).stream()
                    .map(PolicyTestbenchScenarios.PlannedScenario::direction).toList())
                    .as("要素 %s 双向场景齐全", key)
                    .containsExactlyInAnyOrder(PolicyTestbenchScenarios.Direction.ALLOW,
                            PolicyTestbenchScenarios.Direction.DENY);
        }
        assertThat(plans).hasSize(PolicyElementCatalog.keys().size() * 2 + 1);
    }

    @Test
    void judgeQuotaAddedToExistingJudgmentPointWithoutState() throws Exception {
        // 配额纯判定与四要素判定同址（PolicyJudge 只增不改——同一判定位，禁止两处并行定义）
        final Method judgeQuota = PolicyJudge.class.getMethod("judgeQuota",
                UsageControlPolicy.class, int.class);
        assertThat(Modifier.isStatic(judgeQuota.getModifiers())).isTrue();
        assertThat(judgeQuota.getReturnType()).isEqualTo(List.class);
        assertThat(PolicyJudge.class.getMethod("judge", UsageControlPolicy.class, UsageRequest.class,
                LocalDate.class)).isNotNull();
        assertThat(PolicyJudge.class.getDeclaredFields()).isEmpty();
        // 假想上下文上限（应用层校验口径；越界 = 1008C0008）
        assertThat(SimulationContext.MAX_ASSUMED_USED_COUNT).isEqualTo(1_000_000);
    }

    @Test
    void endpointsExposeNoBypassParameter() throws Exception {
        // ① 请求载体入参白名单全等（新增任何入参 —— 包括 force*/bypass*/skipJudgment 类 —— 本锚红）
        assertThat(componentNames(SimulationViews.SimulationRequest.class))
                .containsExactlyInAnyOrder("actionType", "purpose", "territory",
                        "assumedUsedCount", "assumedDate", "strategyDocument");
        assertThat(componentNames(SimulationViews.ExecutionRequest.class))
                .containsExactlyInAnyOrder("actionType", "purpose", "territory");
        for (final String name : componentNames(SimulationViews.SimulationRequest.class)) {
            assertThat(name.toLowerCase(Locale.ROOT)).doesNotContain("force").doesNotContain("bypass")
                    .doesNotContain("skip").doesNotContain("allowed");
        }
        // ② 三端点路径与动词（POST 子资源；无 @RequestParam/@RequestHeader 类旁路入口）
        assertThat(postPaths(ContractPolicySimulationController.class))
                .containsExactlyInAnyOrder("/{contractNo}/policy-simulations",
                        "/{contractNo}/policy-testbench-runs");
        assertThat(postPaths(ContractUsageExecutionController.class))
                .containsExactly("/{contractNo}/usage-executions");
        assertThat(ContractPolicySimulationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/contracts");
        assertThat(ContractUsageExecutionController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/contracts");
        for (final Class<?> controller : List.of(ContractPolicySimulationController.class,
                ContractUsageExecutionController.class)) {
            assertThat(controller.getAnnotation(RestController.class)).as("端点须为 REST 控制器")
                    .isNotNull();
            for (final Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(PostMapping.class)) {
                    continue;
                }
                final List<String> annotationNames = Arrays.stream(method.getParameters())
                        .map(Parameter::getAnnotations)
                        .flatMap(Arrays::stream)
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList();
                assertThat(annotationNames)
                        .as("端点参数仅允许路径变量与请求体（无查询参数/请求头旁路入口）")
                        .allMatch(name -> "PathVariable".equals(name) || "RequestBody".equals(name));
            }
        }
    }

    private static List<String> componentNames(final Class<?> requestType) {
        return Arrays.stream(requestType.getRecordComponents()).map(RecordComponent::getName)
                .toList();
    }

    private static List<String> postPaths(final Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(method -> method.getAnnotation(PostMapping.class))
                .flatMap(mapping -> Arrays.stream(mapping.path()))
                .toList();
    }

    private static UsageControlPolicy allElementsPolicy() {
        return new UsageControlPolicy(UsageControlPolicy.Element.ofCount(true, 100),
                UsageControlPolicy.Element.ofTerm(true, "2027-06-01", "2027-12-31"),
                UsageControlPolicy.Element.ofText(true, "风控建模"),
                UsageControlPolicy.Element.ofText(true, "本市域"),
                UsageControlPolicy.Element.flag(true), false);
    }
}
