package com.ctds.contract.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 合约可见性守卫（WBS-3.4.6 hifi §6，Q6-A——跟踪-17 兑现）：第三消费方出现后抽取的应用层共享
 * 守卫，两种口径 + {@code DENIED_ACCESS} 留痕单点。
 *
 * <p>口径（错误码/文案/留痕动作与 R12 既有实现逐字一致）：</p>
 * <ul>
 *   <li>{@link #requireReadable}：参与方 + 治理（{@code admin} 角色头）通过——R12 /
 *       模拟试算 / 测试台使用；</li>
 *   <li>{@link #requireParticipant}：仅参与方通过（治理不例外）——受控执行使用（治理不发起
 *       使用动作，防"代他人使用"污染计数与流水）。</li>
 * </ul>
 *
 * <p>失败一律 {@code 1008C0012} 与"合约不存在"<b>同码同文逐字</b>（防枚举）+ 留痕；合约不存在与
 * 不可见不可区分。合约域其余守卫（R7/R8/R11）本卡不动（统一收敛沿跟踪-17 登记续办）。</p>
 */
@Service
public class ContractVisibilityGuard {

    /** 演示期平台运营方角色名（治理共用——R9/R10/R11/R12 同款先例）。 */
    private static final String ADMIN_ROLE = "admin";

    private final ContractRepository repository;
    private final Clock clock;

    public ContractVisibilityGuard(final ContractRepository repository, final Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 参与方 + 治理可见性（R12 口径）：通过时返回合约聚合（消费方免二次查询）。
     *
     * @throws ContractBizException 1008C0012 合约不存在或非参与方且非治理（同码同文防枚举）
     */
    public Contract requireReadable(final String contractNo, final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || (contract.roleOf(operatorNo) == null
                && !AuthContext.roles().contains(ADMIN_ROLE))) {
            denyAccessAndLog(contract, contractNo, operatorNo);
        }
        return contract;
    }

    /**
     * 仅参与方可见性（受控执行口径——治理例外不适用）。
     *
     * @throws ContractBizException 1008C0012 合约不存在或非参与方（同码同文防枚举）
     */
    public Contract requireParticipant(final String contractNo, final String operatorNo) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || contract.roleOf(operatorNo) == null) {
            denyAccessAndLog(contract, contractNo, operatorNo);
        }
        return contract;
    }

    /** 拒绝访问单点（同码同文异常 + DENIED_ACCESS 留痕——R12 既有形态逐字抽取）。 */
    private void denyAccessAndLog(final Contract contract, final String contractNo,
            final String operatorNo) {
        repository.insertLog(new ContractActionLog(null, contract == null ? null : contractNo, null,
                ContractAction.DENIED_ACCESS, operatorNo,
                ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_NOT_VISIBLE), null, null,
                LocalDateTime.now(clock)));
        throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
    }
}
