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
 * 受控执行端点（WBS-3.4.6 hifi §4，Q5-A——剧本 C-4.3 S2/S3 幕演示入口，兑现 3.4.5 移交-2）：
 * 真实使用动作（计数递增 + 放行记录 / 越界拒绝留痕），委托既有 {@code check}（零改动）。
 *
 * <p>权限点复用 {@code contract.deal}；可见性 = <b>仅参与方</b>（治理方不发起使用动作——防"代
 * 他人使用"污染计数与流水）；发起方 = 认证主体。放行 HTTP 200；越界 1008C0020 → 403；状态失效
 * 1008C0013 → 409；不存在/非参与方 1008C0012 → 404（同码同文防枚举）；参数非法 1008C0008 →
 * 400。<b>无幂等键</b>（沿 ADR-020 §2.7：按调用计数语义下重试 = 新一次调用）。</p>
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractUsageExecutionController {

    private final PolicySimulationService simulationService;

    public ContractUsageExecutionController(final PolicySimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /** 受控执行使用动作（演示期受控调用入口——非交付链本体，ADR-021 §2.8 诚实边界）。 */
    @PostMapping(path = "/{contractNo}/usage-executions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.deal")
    public ApiResult<SimulationViews.Execution> execute(@PathVariable final String contractNo,
            @RequestBody final SimulationViews.ExecutionRequest request) {
        return ApiResult.ok(SimulationViews.of(contractNo, simulationService.executeUsage(
                new PolicySimulationService.ExecutionCommand(contractNo, request.actionType(),
                        request.purpose(), request.territory()), AuthContext.subject())));
    }
}
