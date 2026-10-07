package com.ctds.contract.application;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.PolicyJudge;
import com.ctds.contract.domain.policy.PolicyViolation;
import com.ctds.contract.domain.policy.UsageLogEntry;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.ctds.contract.infrastructure.UsageCounterStore;
import com.ctds.contract.infrastructure.UsageLogRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策略执行判定入口（WBS-3.4.5 hifi §2/§3——应用层方法，无 HTTP 端点）：编排
 * QC1 消费（步 1 合约定位 + 步 2 状态门槛）→ 空策略/显式无限制（步 3 放行不计数）→
 * {@link PolicyJudge} 全查（步 4~7）→ 配额判检一体（步 9）→ 放行/拒绝记录（步 8/10）。
 *
 * <p>判定语义唯一权威 = 3.4.4 {@code PolicyElementCatalog}（引擎只消费不复制）；判定与
 * 计数统一取注入 {@code Clock}（两把钟教训）。集成面契约（供 3.5.2/C-5.x 按 ADR-020 §5
 * 接入）：同步判定、同步返回、放行即计数——消费方不得在判定与实际交付之间假设持有
 * "放行凭证"（判定时点语义）。</p>
 *
 * <p>事务口径（hifi §7-2）：本方法为外层事务边界；放行腿"计数递增 + 放行记录"同事务提交
 * （仓储 REQUIRED 参与）；拒绝留痕走独立事务（REQUIRES_NEW）——拒绝留痕是拒绝的一部分，
 * 必须超越拒绝异常的回滚存活；拒绝腿在判定步 9 之前不触碰计数表，故"拒绝不烧次数"由
 * 结构性保证。</p>
 */
@Service
public class PolicyExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyExecutionService.class);

    private final ContractQueryService contractQueryService;
    private final UsageCounterStore counterStore;
    private final UsageLogRepository logRepository;
    private final Clock clock;

    public PolicyExecutionService(final ContractQueryService contractQueryService,
            final UsageCounterStore counterStore, final UsageLogRepository logRepository,
            final Clock clock) {
        this.contractQueryService = contractQueryService;
        this.counterStore = counterStore;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    /**
     * 使用判定入口（唯一权威——服务端强制，规格行为 5 规则 3）。
     *
     * <p>放行：递增计数 + 写放行记录；拒绝：写拒绝留痕（含触发要素全查明细），
     * 零计数、零计数副作用（拒绝不烧次数）。</p>
     *
     * @throws ContractBizException 1008C0013 合约未生效/已终止/已完结（策略失效，S3-7 联动）；
     *         1008C0012 合约不存在（防枚举同形，沿 R6~R11 口径）；1008C0020 越界使用
     *         （五类拦截，触发要素入留痕与日志）
     */
    @Transactional
    public UsageVerdict check(final String contractNo, final UsageRequest request) {
        final LocalDateTime now = LocalDateTime.now(clock);
        // 步 1 合约定位（不存在 → C0012 防枚举同形；引擎不做参与方可见性二判——R12 承载可见性）
        final ContractQueryService.ContractStrategySnapshot snapshot =
                contractQueryService.loadEffectiveStrategy(contractNo);
        if (snapshot == null) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                    ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
        }
        // 步 2 状态门槛（未生效/已终止/已完结 → 策略同步失效 C0013 + 拒绝留痕——移交-5 承接 S3-7）
        if (!ContractStatus.EFFECTIVE.name().equals(snapshot.status())) {
            logRepository.insertDenied(denial(contractNo, request,
                    ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_STATE_FORBIDDEN),
                    UsageVerdict.denied(0, List.of(), now)));
            log.warn("合约状态不允许使用: contractNo={}, status={}", contractNo, snapshot.status());
            throw new ContractBizException(ContractErrorCodes.CONTRACT_STATE_FORBIDDEN,
                    ContractErrorCodes.CONTRACT_STATE_FORBIDDEN_MESSAGE);
        }
        final UsageControlPolicy strategy = snapshot.strategy();
        // 步 3 空策略 / 显式无限制（放行不计数——无配额可计，usedCount 上限口径 = 无上限）
        if (strategy == null || strategy.noRestrictionDeclared()) {
            logRepository.insertAllowed(allow(contractNo, request, now, 0));
            return UsageVerdict.allowed(0, now);
        }
        // 步 4~7 四要素全查（拒绝零计数副作用——拒绝腿不触碰计数表）
        final List<PolicyViolation> violations =
                PolicyJudge.judge(strategy, request, LocalDate.now(clock));
        if (!violations.isEmpty()) {
            final UsageVerdict denied =
                    UsageVerdict.denied(counterStore.currentCount(contractNo), violations, now);
            logRepository.insertDenied(denial(contractNo, request,
                    ContractErrorCodes.tailOf(ContractErrorCodes.POLICY_USAGE_DENIED), denied));
            log.warn("越界使用被拒绝: contractNo={}, requesterNo={}, violations={}", contractNo,
                    request.requesterNo(), denied.violations());
            throw new ContractBizException(ContractErrorCodes.POLICY_USAGE_DENIED,
                    ContractErrorCodes.POLICY_USAGE_DENIED_MESSAGE);
        }
        // 步 9 配额判检一体（quota 未启用 → 不计数直接放行；影响行数 = 0 → 耗尽拒绝）
        int usedCount = 0;
        if (strategy.quota() != null && strategy.quota().enabled()) {
            final Integer used =
                    counterStore.tryIncrement(contractNo, strategy.quota().maxCount(), now);
            if (used == null) {
                logRepository.insertDenied(denial(contractNo, request,
                        ContractErrorCodes.tailOf(ContractErrorCodes.POLICY_USAGE_DENIED),
                        UsageVerdict.denied(counterStore.currentCount(contractNo),
                                List.of(PolicyViolation.QUOTA_EXHAUSTED), now)));
                log.warn("使用次数已耗尽: contractNo={}, requesterNo={}, limit={}", contractNo,
                        request.requesterNo(), strategy.quota().maxCount());
                throw new ContractBizException(ContractErrorCodes.POLICY_USAGE_DENIED,
                        ContractErrorCodes.POLICY_USAGE_DENIED_MESSAGE);
            }
            usedCount = used;
        }
        // 步 10 放行记录（与计数递增同事务提交）
        logRepository.insertAllowed(allow(contractNo, request, now, usedCount));
        return UsageVerdict.allowed(usedCount, now);
    }

    private static UsageLogEntry allow(final String contractNo, final UsageRequest request,
            final LocalDateTime occurredAt, final int usedCount) {
        return new UsageLogEntry(contractNo, request.requesterNo(), request.actionType(),
                UsageLogEntry.UsageOutcome.ALLOWED, null, null, usedCount, occurredAt);
    }

    /** 拒绝留痕装配（来源 = 判定结果对象——触发要素明细与不变计数同源，hifi §5 used_count 口径）。 */
    private static UsageLogEntry denial(final String contractNo, final UsageRequest request,
            final String reasonCode, final UsageVerdict verdict) {
        return new UsageLogEntry(contractNo, request.requesterNo(), request.actionType(),
                UsageLogEntry.UsageOutcome.DENIED, reasonCode,
                verdict.violations().isEmpty() ? null : violationCodes(verdict.violations()),
                verdict.usedCount(), verdict.occurredAt());
    }

    private static String violationCodes(final List<PolicyViolation> violations) {
        return violations.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
