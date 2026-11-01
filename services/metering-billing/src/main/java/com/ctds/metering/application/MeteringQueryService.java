package com.ctds.metering.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.metering.domain.ContractOwnership;
import com.ctds.metering.domain.ContractOwnershipReader;
import com.ctds.metering.domain.MeteringFlow;
import com.ctds.metering.domain.MeteringFlowRepository;
import com.ctds.metering.domain.MeteringReconcileReader;
import com.ctds.metering.domain.PeriodFlowSummary;
import com.ctds.metering.domain.ReconcileCounts;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 计量读面（R-M1/R-M2/R-M3，hifi §1）：本方读面带 B8-4 归属校验——合约不存在与非本方
 * 同码同文 404 防枚举（1011C0008）；勾稽读数 = 运营治理读面（不做归属校验），纯读数不判差异。
 */
@Service
public class MeteringQueryService {

    /** 合约号形态（CO+6 位，沿 contract uk_contract_no 同域；前缀两字母 + 6 位数字）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");

    private final ContractOwnershipReader ownershipReader;
    private final MeteringFlowRepository flowRepository;
    private final MeteringReconcileReader reconcileReader;

    public MeteringQueryService(final ContractOwnershipReader ownershipReader,
            final MeteringFlowRepository flowRepository,
            final MeteringReconcileReader reconcileReader) {
        this.ownershipReader = ownershipReader;
        this.flowRepository = flowRepository;
        this.reconcileReader = reconcileReader;
    }

    /** R-M1 本方合约计量流水（B7-1 读面 + B8-4 归属校验服务端强制）。 */
    public ContractFlow flows(final String contractNo, final String subject) {
        final ContractOwnership ownership = assertReadable(contractNo, subject);
        final List<FlowEntry> items = flowRepository.findByContract(contractNo).stream()
                .map(MeteringQueryService::toEntry).toList();
        return new ContractFlow(contractNo, ownership.requesterNo(), ownership.providerNo(), items);
    }

    /** R-M2 本方合约按自然月聚合（B8-1 查询承载，Q2-A/Q3-A）。 */
    public ContractSummary summary(final String contractNo, final String subject) {
        assertReadable(contractNo, subject);
        return new ContractSummary(contractNo, summarize(contractNo));
    }

    /** R-M3 四方勾稽读数（运营治理读面；纯读数展示，无差异处置语义——B11 归 3.7.6/3.7.8）。 */
    public ReconcileSnapshot reconcile(final String contractNo) {
        assertContractNoFormat(contractNo);
        final ReconcileCounts counts = reconcileReader.readCounts(contractNo);
        return new ReconcileSnapshot(contractNo, counts.deliveryFileCount(),
                counts.usageLogAllowedCount(), counts.usageCounterUsedCount(),
                flowRepository.countByContract(contractNo), summarize(contractNo));
    }

    private List<PeriodSummary> summarize(final String contractNo) {
        return flowRepository.summarizeByPeriod(contractNo).stream()
                .map(MeteringQueryService::toPeriod).toList();
    }

    /** B8-4 归属校验（单一权威 = contract 双方字段实时读）：非本方与不存在同码同文 404。 */
    private ContractOwnership assertReadable(final String contractNo, final String subject) {
        assertContractNoFormat(contractNo);
        final ContractOwnership ownership = ownershipReader.findByContractNo(contractNo)
                .orElseThrow(MeteringQueryService::notVisible);
        if (subject == null || (!subject.equals(ownership.requesterNo())
                && !subject.equals(ownership.providerNo()))) {
            throw notVisible();
        }
        return ownership;
    }

    private void assertContractNoFormat(final String contractNo) {
        if (contractNo == null || contractNo.isBlank()
                || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
    }

    private static BizException notVisible() {
        return new BizException(com.ctds.metering.domain.MeteringErrorCodes.METERING_NOT_VISIBLE,
                com.ctds.metering.domain.MeteringErrorCodes.METERING_NOT_VISIBLE_MESSAGE);
    }

    private static FlowEntry toEntry(final MeteringFlow flow) {
        return new FlowEntry(flow.sourceType(), flow.sourceNo(), flow.action(), flow.resource(),
                flow.occurredAt());
    }

    private static PeriodSummary toPeriod(final PeriodFlowSummary summary) {
        return new PeriodSummary(summary.period(), summary.flowCount(), summary.lastEventAt());
    }
}
