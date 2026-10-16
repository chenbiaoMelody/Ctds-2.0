package com.ctds.contract.application;

import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.ctds.contract.interfaces.dto.InternalUsageCheckViews;
import org.springframework.stereotype.Service;

/**
 * 内部判定入口（WBS-3.5.2 hifi §4.1，ADR-020 §2.8 集成面契约的消费方 = 网关交付链）：
 * <b>零第二语义</b>——① 参与方可见性复用 {@link ContractVisibilityGuard#requireParticipant}
 * （非参与方 / 不存在 → 1008C0012 同码同文防枚举 + 越权留痕）；② 判定复用
 * {@link PolicyExecutionService#check}（状态门槛 1008C0013 / 越界 1008C0020 / 配额判检一体全沿既有链路）。
 *
 * <p>与受控执行端点（3.4.6 / ADR-021 演示形态）的分工：本入口的判定主体由请求体显式给出（服务身份
 * 调用方代交付链询问），供网关在转发前获取"是否越界 + 合约需求方是谁"；<b>不复用</b>演示期
 * {@code usage-executions}。</p>
 *
 * @throws ContractBizException 1008C0008 请求参数不合法（合约编号 / 判定主体缺失或动作类型非法）；
 *         1008C0012 合约不存在或非参与方（同码同文防枚举）；1008C0013 合约状态失效；
 *         1008C0020 越界使用
 */
@Service
public class InternalUsageCheckService {

    private final ContractVisibilityGuard visibilityGuard;
    private final PolicyExecutionService policyExecutionService;

    public InternalUsageCheckService(final ContractVisibilityGuard visibilityGuard,
            final PolicyExecutionService policyExecutionService) {
        this.visibilityGuard = visibilityGuard;
        this.policyExecutionService = policyExecutionService;
    }

    public InternalUsageCheckViews.Result check(final Command command) {
        final String contractNo = requireText(command.contractNo());
        final String requesterNo = requireText(command.requesterNo());
        final UsageActionType actionType = parseActionType(command.actionType());
        final Contract contract = visibilityGuard.requireParticipant(contractNo, requesterNo);
        final UsageVerdict verdict = policyExecutionService.check(contractNo,
                new UsageRequest(requesterNo, actionType, command.purpose(), command.territory()));
        return new InternalUsageCheckViews.Result(
                new InternalUsageCheckViews.Verdict(verdict.allowed(), verdict.usedCount(),
                        verdict.violations().stream().map(Enum::name).toList(), verdict.occurredAt()),
                new InternalUsageCheckViews.ContractSummary(contract.requesterSubjectNo(),
                        contract.providerSubjectNo(), contract.status().name(), contract.effectiveAt()));
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw paramInvalid();
        }
        return value.trim();
    }

    private static UsageActionType parseActionType(final String actionType) {
        if (actionType == null) {
            throw paramInvalid();
        }
        try {
            return UsageActionType.valueOf(actionType.trim());
        } catch (final IllegalArgumentException ex) {
            throw paramInvalid();
        }
    }

    private static ContractBizException paramInvalid() {
        return new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE);
    }

    /** 判定命令（interfaces 层装配，应用层不依赖视图 DTO）。 */
    public record Command(String contractNo, String requesterNo, String actionType, String purpose,
            String territory) {
    }
}
