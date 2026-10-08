package com.ctds.contract.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAction;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.UsageControlPolicy;
import com.ctds.contract.domain.policy.UsageLogEntry;
import com.ctds.contract.infrastructure.UsageCounterStore;
import com.ctds.contract.infrastructure.UsageLogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 使用摘要与记录查询服务（WBS-3.4.5 hifi §6 R12）：本合约已用次数/上限 + 放行/拒绝统计 +
 * 记录分页。可见性沿 R6~R11 权限口径——合约参与方（提供方/需求方）+ 治理（admin 角色头）；
 * 非参与方与"不存在"同码同文逐字（1008C0012 防枚举）+ DENIED_ACCESS 留痕。
 */
@Service
public class UsageQueryService {

    /** 演示期平台运营方角色名（治理共用——R9/R10/R11 同款先例）。 */
    private static final String ADMIN_ROLE = "admin";

    private final ContractRepository repository;
    private final ContractQueryService contractQueryService;
    private final UsageCounterStore counterStore;
    private final UsageLogRepository logRepository;
    private final Clock clock;

    public UsageQueryService(final ContractRepository repository,
            final ContractQueryService contractQueryService, final UsageCounterStore counterStore,
            final UsageLogRepository logRepository, final Clock clock) {
        this.repository = repository;
        this.contractQueryService = contractQueryService;
        this.counterStore = counterStore;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    /** R12 使用摘要（参与方 + 治理可见——非参与方 C0012 防枚举同形 + 留痕）。 */
    public UsageSummary summary(final String contractNo, final String operatorNo,
            final PageQuery page) {
        final Contract contract = repository.findByNo(contractNo).orElse(null);
        if (contract == null || (contract.roleOf(operatorNo) == null
                && !AuthContext.roles().contains(ADMIN_ROLE))) {
            denyAccessAndLog(contract, contractNo, operatorNo);
        }
        final long allowedCount =
                logRepository.countByOutcome(contractNo, UsageLogEntry.UsageOutcome.ALLOWED.name());
        final long deniedCount =
                logRepository.countByOutcome(contractNo, UsageLogEntry.UsageOutcome.DENIED.name());
        return new UsageSummary(contractNo, quotaOf(contractNo), allowedCount, deniedCount,
                logRepository.pageByContract(contractNo, page));
    }

    /** 配额视图（仅生效策略的 quota 要素启用时非空——非生效/未启用 = null，沿 R12 契约）。 */
    private QuotaInfo quotaOf(final String contractNo) {
        final ContractQueryService.ContractStrategySnapshot snapshot =
                contractQueryService.loadEffectiveStrategy(contractNo);
        if (snapshot == null || snapshot.strategy() == null) {
            return null;
        }
        final UsageControlPolicy.Element quota = snapshot.strategy().quota();
        if (quota == null || !quota.enabled()) {
            return null;
        }
        return new QuotaInfo(quota.maxCount(), counterStore.currentCount(contractNo));
    }

    /** 参与方/治理可见性（非参与方与不存在同码同文逐字 + DENIED_ACCESS 留痕——沿 R6~R11 口径）。 */
    private void denyAccessAndLog(final Contract contract, final String contractNo,
            final String operatorNo) {
        repository.insertLog(new ContractActionLog(null, contract == null ? null : contractNo, null,
                ContractAction.DENIED_ACCESS, operatorNo,
                ContractErrorCodes.tailOf(ContractErrorCodes.CONTRACT_NOT_VISIBLE), null, null,
                LocalDateTime.now(clock)));
        throw new ContractBizException(ContractErrorCodes.CONTRACT_NOT_VISIBLE,
                ContractErrorCodes.CONTRACT_NOT_VISIBLE_MESSAGE);
    }

    /**
     * R12 摘要载体（quota 为 null = 当前无生效配额口径——策略未生效/quota 未启用）。
     *
     * @param contractNo   合约编号
     * @param quota        配额视图（limit = 策略上限，used = 已用次数）
     * @param allowedCount 放行次数统计
     * @param deniedCount  拒绝次数统计
     * @param records      执行记录分页（最新在前）
     */
    public record UsageSummary(String contractNo, QuotaInfo quota, long allowedCount,
            long deniedCount, PageResult<UsageLogEntry> records) {
    }

    /** 配额视图（判定口径 = counter 余额）。 */
    public record QuotaInfo(int limit, int used) {
    }
}
