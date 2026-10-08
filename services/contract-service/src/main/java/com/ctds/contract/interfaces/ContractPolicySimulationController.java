package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.PolicySimulationService;
import com.ctds.contract.interfaces.dto.SimulationViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略模拟器与测试台端点（WBS-3.4.6 hifi §2/§3，Q1-A/Q2-A/Q4-A）：模拟试算（零副作用，结论以
 * 数据返回）+ 测试台（目录驱动 11 场景报告，零落库）。
 *
 * <p>权限点复用 {@code contract.deal}（沿合约域既有权限点面，不新增）；可见性 = 参与方 + 治理，
 * 判定在应用层共享守卫单点（非参与方与"不存在"同码同文 1008C0012 防枚举 + 留痕）。两端点均
 * <b>不提供任何"跳过判定/强制放行"参数</b>（fail-closed，规格行为 5 规则 3）。</p>
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractPolicySimulationController {

    private final PolicySimulationService simulationService;

    public ContractPolicySimulationController(final PolicySimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /** 模拟试算（假想上下文；判定拒绝亦 HTTP 200 + `allowed=false`）。 */
    @PostMapping(path = "/{contractNo}/policy-simulations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<SimulationViews.Simulation> simulate(@PathVariable final String contractNo,
            @RequestBody final SimulationViews.SimulationRequest request) {
        return ApiResult.ok(SimulationViews.of(simulationService.simulate(contractNo,
                new PolicySimulationService.SimulationCommand(request.actionType(),
                        request.purpose(), request.territory(), request.assumedUsedCount(),
                        request.assumedDate(), request.strategyDocument()),
                AuthContext.subject())));
    }

    /** 测试台（双向场景集一键执行——报告零落库）。 */
    @PostMapping(path = "/{contractNo}/policy-testbench-runs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<SimulationViews.TestbenchReport> testbench(@PathVariable final String contractNo) {
        return ApiResult.ok(SimulationViews.of(
                simulationService.runTestbench(contractNo, AuthContext.subject())));
    }
}
