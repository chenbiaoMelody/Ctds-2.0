package com.ctds.contract.application;

import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.OrderRepository;
import com.ctds.contract.domain.TerminationType;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合约状态与订单状态事务联动（WBS-3.7.3 hifi §3.3；规格 B3-4 两条联动）。
 * 挂接 {@code ContractCommandService.sign / terminate}：合约写面与订单状态更新
 * <b>同事务原子提交</b>（DID 解析/代签仍在事务外——由 ContractCommandService 先行，本服务
 * 只承接合约仓储方法 + 订单联动；失败整体回滚，无滞后——Q3-A）。
 *
 * <p>只映射规格 B3-4 两条联动（Q7-A）：合约生效（后签 EFFECTED）→ 订单 COMPLETED；
 * 生效前终止（NEGOTIATION_TERMINATED / SIGNATURE_REFUSED）→ 订单 CLOSED。首签
 * （FIRST_SIGNED）不动订单；合约生效后的合意完结 / 强制终止不改订单状态（无订单的合约
 * ——经 W5 直接发起——为 no-op；条件更新仅 IN_PROGRESS 行命中，重复同步零二次留痕）。</p>
 */
@Service
public class ContractOrderSyncService {

    /** 关闭原因业务文案（hifi §3.3：不落内部终止类型码 / 理由原文）。 */
    private static final String CLOSE_REASON_NEGOTIATION = "合约协商终止";
    private static final String CLOSE_REASON_SIGNATURE_REFUSED = "合约拒签终止";

    private final ContractRepository contractRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public ContractOrderSyncService(final ContractRepository contractRepository,
            final OrderRepository orderRepository, final Clock clock) {
        this.contractRepository = contractRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /** 电子签署 + 订单联动（合约仓储方法加入外层事务；EFFECTED 才动订单）。 */
    @Transactional
    public ContractRepository.SignOutcome signAndSync(
            final ContractRepository.SignCommand command) {
        final ContractRepository.SignOutcome outcome = contractRepository.sign(command);
        if (outcome == ContractRepository.SignOutcome.EFFECTED) {
            orderRepository.completeByContract(command.signLog().contractNo(),
                    LocalDateTime.now(clock), command.subjectNo());
        }
        return outcome;
    }

    /** 终止 + 订单联动（合约仓储方法加入外层事务；生效前终止两类才动订单）。 */
    @Transactional
    public ContractRepository.TerminateOutcome terminateAndSync(
            final ContractRepository.TerminateCommand command) {
        final ContractRepository.TerminateOutcome outcome = contractRepository.terminate(command);
        if (outcome == ContractRepository.TerminateOutcome.TERMINATED
                && (command.terminationType() == TerminationType.NEGOTIATION_TERMINATED
                || command.terminationType() == TerminationType.SIGNATURE_REFUSED)) {
            orderRepository.closeByContract(command.contractNo(),
                    closeReasonOf(command.terminationType()), LocalDateTime.now(clock),
                    command.terminatedBy());
        }
        return outcome;
    }

    private String closeReasonOf(final TerminationType type) {
        return type == TerminationType.NEGOTIATION_TERMINATED
                ? CLOSE_REASON_NEGOTIATION : CLOSE_REASON_SIGNATURE_REFUSED;
    }
}
