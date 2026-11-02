package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.metering.application.billing.BillingEngine.BillComputation;
import com.ctds.metering.application.billing.BillingEngine.ComputedLine;
import com.ctds.metering.domain.billing.Bill;
import com.ctds.metering.domain.billing.BillActionLog;
import com.ctds.metering.domain.billing.BillDetailLine;
import com.ctds.metering.domain.billing.BillDetailRepository;
import com.ctds.metering.domain.billing.BillActionLogRepository;
import com.ctds.metering.domain.billing.BillNoGenerator;
import com.ctds.metering.domain.billing.BillRepository;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillType;
import com.ctds.metering.domain.billing.BillingAction;
import com.ctds.metering.domain.billing.BillingContractReader;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import com.ctds.metering.domain.billing.BillingPricingModel;
import com.ctds.metering.domain.billing.BillingUsageReader;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import com.ctds.metering.domain.billing.MeteringEventRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出账编排（hifi §3.3；手动 B-W1 与自动周期腿共用）：
 * 读合约快照 → 校验可出账条件 → 计算 → 取号 → 账单 + 明细 + 动作留痕单事务落库。
 * 同合约同周期幂等由生成列唯一键兜底（重复 → 1011C0010，整事务回滚不留半单）。
 */
@Service
public class BillingOutbillService {

    /** 合约号形态（CO+6 位，沿计量读面同口径）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");
    /** 自动出账主体标识（hifi §3.3-5）。 */
    public static final String SYSTEM_OPERATOR = "system";

    private final BillingContractReader contractReader;
    private final BillingUsageReader usageReader;
    private final BillRepository billRepository;
    private final BillDetailRepository detailRepository;
    private final BillActionLogRepository actionLogRepository;
    private final BillNoGenerator billNoGenerator;
    private final BillingEngine engine;
    private final Clock clock;

    public BillingOutbillService(final BillingContractReader contractReader,
            final BillingUsageReader usageReader,
            final BillRepository billRepository,
            final BillDetailRepository detailRepository,
            final BillActionLogRepository actionLogRepository,
            final BillNoGenerator billNoGenerator,
            final BillingEngine engine,
            final Clock clock) {
        this.contractReader = contractReader;
        this.usageReader = usageReader;
        this.billRepository = billRepository;
        this.detailRepository = detailRepository;
        this.actionLogRepository = actionLogRepository;
        this.billNoGenerator = billNoGenerator;
        this.engine = engine;
        this.clock = clock;
    }

    /** 手动即时出账（B-W1，B10-1 手动腿）：period 缺省 = 当前自然月。 */
    @Transactional
    public String outbill(final String contractNo, final String period,
            final BigDecimal agreedTransactionAmount, final String operator) {
        return doOutbill(contractNo, period, agreedTransactionAmount, operator);
    }

    /** 自动周期出账（B10-1 周期腿）：主体固定 system，分成档不在自动腿（Q3-A）。 */
    @Transactional
    public String autoOutbill(final String contractNo, final String period) {
        return doOutbill(contractNo, period, null, SYSTEM_OPERATOR);
    }

    private String doOutbill(final String contractNo, final String period,
            final BigDecimal agreedTransactionAmount, final String operator) {
        assertContractNo(contractNo);
        final YearMonth month = resolveMonth(period);
        final ContractPricingSnapshot contract = contractReader.findByContractNo(contractNo)
                .orElseThrow(BillingOutbillService::contractNotFound);
        assertBillable(contract, month);
        final List<MeteringEventRow> events = contract.pricingModel() == BillingPricingModel.PER_CALL
                ? usageReader.events(contractNo, BillingPeriods.start(month), BillingPeriods.end(month))
                : List.of();
        final BillComputation computation = engine.compute(contract, events, agreedTransactionAmount);
        return persist(contract, month, computation, operator);
    }

    private String persist(final ContractPricingSnapshot contract, final YearMonth month,
            final BillComputation computation, final String operator) {
        final LocalDateTime now = LocalDateTime.now(clock);
        final String billNo = billNoGenerator.nextBillNo();
        final Bill bill = new Bill(0L, billNo, BillType.NORMAL, contract.contractNo(),
                contract.requesterNo(), contract.providerNo(), contract.productId(),
                contract.productName(), BillingPeriods.text(month), contract.pricingModel(),
                computation.quantity(), computation.unitPrice(), computation.baseAmount(),
                computation.shareRate(), computation.amount(), BillStatus.ISSUED, null, null,
                operator, now);
        try {
            billRepository.insert(bill);
        } catch (final DuplicateKeyException e) {
            // 唯一键 uk_active_normal_key：同合约同周期重复出账（B10-1 幂等负向）
            throw new BizException(BillingErrorCodes.BILL_DUPLICATED,
                    BillingErrorCodes.BILL_DUPLICATED_MESSAGE);
        }
        final List<BillDetailLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (final ComputedLine line : computation.lines()) {
            lines.add(new BillDetailLine(0L, billNo, lineNo, line.lineType(), line.description(),
                    line.meteringFlowId(), line.sourceType(), line.sourceNo(), line.occurredAt(),
                    line.quantity(), line.unitPrice(), line.amount()));
            lineNo++;
        }
        detailRepository.insertAll(lines);
        actionLogRepository.insert(new BillActionLog(billNo, BillingAction.CREATE, null,
                BillStatus.ISSUED, "账单生成（" + contract.pricingModel() + "）", operator, now));
        return billNo;
    }

    /** 周期解析：缺省当前月；格式非法或晚于当前月 → 1000C0001（hifi §1 注）。 */
    private YearMonth resolveMonth(final String period) {
        final YearMonth current = YearMonth.now(clock);
        if (period == null || period.isBlank()) {
            return current;
        }
        final YearMonth month = BillingPeriods.parse(period.trim());
        if (month.isAfter(current)) {
            throw new BizException(com.ctds.common.errorcode.ErrorCodes.PARAM_INVALID,
                    "参数格式不合法");
        }
        return month;
    }

    /** 合约可出账条件：生效 ≤ 周期末 且（未终止 或 终止 > 周期初）（hifi §3.2 / Q5-A）。 */
    private void assertBillable(final ContractPricingSnapshot contract, final YearMonth month) {
        final LocalDateTime start = BillingPeriods.start(month);
        final LocalDateTime end = BillingPeriods.end(month);
        if (contract.effectiveAt() == null || contract.effectiveAt().isAfter(end)) {
            throw notBillable();
        }
        if (contract.endedAt() != null && !contract.endedAt().isAfter(start)) {
            throw notBillable();
        }
    }

    private static void assertContractNo(final String contractNo) {
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw new BizException(com.ctds.common.errorcode.ErrorCodes.PARAM_INVALID,
                    "参数格式不合法");
        }
    }

    private static BizException contractNotFound() {
        return new BizException(BillingErrorCodes.CONTRACT_NOT_FOUND,
                BillingErrorCodes.CONTRACT_NOT_FOUND_MESSAGE);
    }

    private static BizException notBillable() {
        return new BizException(BillingErrorCodes.CONTRACT_NOT_BILLABLE,
                BillingErrorCodes.CONTRACT_NOT_BILLABLE_MESSAGE);
    }
}
