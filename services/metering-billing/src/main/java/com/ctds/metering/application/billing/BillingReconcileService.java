package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.metering.application.billing.ReconciliationView.ReconciliationDifference;
import com.ctds.metering.domain.billing.Bill;
import com.ctds.metering.domain.billing.BillActionLog;
import com.ctds.metering.domain.billing.BillActionLogRepository;
import com.ctds.metering.domain.billing.BillDetailRepository;
import com.ctds.metering.domain.billing.BillRepository;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillingAction;
import com.ctds.metering.domain.billing.BillingContractReader;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import com.ctds.metering.domain.billing.BillingPricingModel;
import com.ctds.metering.domain.billing.BillingUsageMetrics;
import com.ctds.metering.domain.billing.BillingUsageReader;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import com.ctds.metering.domain.billing.MeteringEventRow;
import com.ctds.metering.domain.billing.Reconciliation;
import com.ctds.metering.domain.billing.ReconciliationConclusion;
import com.ctds.metering.domain.billing.ReconciliationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对账（hifi §5，B11 硬约束）：四方交叉验证 + 金额复算腿。
 * 计数腿 = 全量余额（counter）/ 全量 ALLOWED 流水 / 全量计量；本期腿 = 本期 ALLOWED 流水 / 本期计量 / 按次账单数量；
 * 金额腿 = 账单金额 vs 按账单快照独立复算。差异必报（不静默通过）→ 相关账单冻结 fail-closed；
 * 通过 → 解冻恢复原状态；除状态流转与留痕外零数据修改（不自动修数，B11-4）。
 */
@Service
public class BillingReconcileService {

    /** 差异类型常量（details_json.type，业务可读口径见 §5-3）。 */
    public static final String COUNTER_VS_USAGE_LOG_ALL = "COUNTER_VS_USAGE_LOG_ALL";
    public static final String USAGE_LOG_VS_METERING_ALL = "USAGE_LOG_VS_METERING_ALL";
    public static final String USAGE_LOG_VS_METERING_PERIOD = "USAGE_LOG_VS_METERING_PERIOD";
    public static final String BILL_QUANTITY_VS_METERING = "BILL_QUANTITY_VS_METERING";
    public static final String AMOUNT_RECOMPUTE = "AMOUNT_RECOMPUTE";
    public static final String MISSING_IN_BILL = "MISSING_IN_BILL";
    public static final String EXTRA_IN_BILL = "EXTRA_IN_BILL";

    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_DETAIL_ITEMS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BillingContractReader contractReader;
    private final BillingUsageReader usageReader;
    private final BillRepository billRepository;
    private final BillDetailRepository detailRepository;
    private final BillActionLogRepository actionLogRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final Clock clock;

    public BillingReconcileService(final BillingContractReader contractReader,
            final BillingUsageReader usageReader,
            final BillRepository billRepository,
            final BillDetailRepository detailRepository,
            final BillActionLogRepository actionLogRepository,
            final ReconciliationRepository reconciliationRepository,
            final Clock clock) {
        this.contractReader = contractReader;
        this.usageReader = usageReader;
        this.billRepository = billRepository;
        this.detailRepository = detailRepository;
        this.actionLogRepository = actionLogRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.clock = clock;
    }

    /** 执行对账（B-W4）：差异 → 冻结；通过 → 解冻；两种情况都落一行对账记录并留痕。 */
    @Transactional
    public ReconciliationView reconcile(final String contractNo, final String period,
            final String operator) {
        assertContractNo(contractNo);
        final YearMonth month = BillingPeriods.parse(period);
        final String periodText = BillingPeriods.text(month);
        final ContractPricingSnapshot contract = contractReader.findByContractNo(contractNo)
                .orElseThrow(() -> new BizException(BillingErrorCodes.CONTRACT_NOT_FOUND,
                        BillingErrorCodes.CONTRACT_NOT_FOUND_MESSAGE));
        final Bill normal = billRepository.findNormal(contract.contractNo(), periodText)
                .orElseThrow(BillingReconcileService::notVisible);

        final BillingUsageMetrics metrics = usageReader.metrics(contractNo,
                BillingPeriods.start(month), BillingPeriods.end(month));
        final List<MeteringEventRow> events = usageReader.events(contractNo,
                BillingPeriods.start(month), BillingPeriods.end(month));
        final BigDecimal recomputed = recompute(normal);
        final List<ReconciliationDifference> differences = judge(normal, metrics, events, recomputed);

        final ReconciliationConclusion conclusion = differences.isEmpty()
                ? ReconciliationConclusion.PASS : ReconciliationConclusion.DIFFERENCE;
        final LocalDateTime now = LocalDateTime.now(clock);
        if (conclusion == ReconciliationConclusion.DIFFERENCE) {
            freezeBills(contractNo, periodText, operator, now);
        } else {
            restoreBills(contractNo, periodText, operator, now);
        }

        final Integer billQuantity = normal.pricingModel() == BillingPricingModel.PER_CALL
                ? normal.quantity() : null;
        final int counterUsedCount = metrics.counterUsedCount() == null ? 0 : metrics.counterUsedCount();
        final Reconciliation reconciliation = new Reconciliation(0L, contractNo, periodText,
                counterUsedCount, metrics.usageLogAllowedPeriod(), metrics.meteringFlowPeriod(),
                metrics.usageLogAllowedAllTime(), metrics.meteringFlowAllTime(), billQuantity,
                normal.amount(), recomputed, conclusion, writeDifferences(differences), operator, now);
        reconciliationRepository.insert(reconciliation);
        return new ReconciliationView(reconciliation.id(), contractNo, periodText, counterUsedCount,
                metrics.usageLogAllowedPeriod(), metrics.meteringFlowPeriod(),
                metrics.usageLogAllowedAllTime(), metrics.meteringFlowAllTime(), billQuantity,
                normal.amount().toPlainString(), recomputed.toPlainString(), conclusion.name(),
                differences, operator, now);
    }

    /** 按账单快照独立复算（不复用计费引擎代码路径——交叉验证语义，同输入同输出 B9-4）。 */
    private static BigDecimal recompute(final Bill bill) {
        final BillingPricingModel model = bill.pricingModel();
        return switch (model) {
            case PER_CALL -> bill.unitPrice()
                    .multiply(BigDecimal.valueOf(bill.quantity() == null ? 0 : bill.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            case MONTHLY -> bill.unitPrice().setScale(2, RoundingMode.HALF_UP);
            case REVENUE_SHARE -> bill.baseAmount().multiply(bill.shareRate())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            case FREE -> bill.amount();
        };
    }

    private List<ReconciliationDifference> judge(final Bill normal,
            final BillingUsageMetrics metrics, final List<MeteringEventRow> events,
            final BigDecimal recomputed) {
        final List<ReconciliationDifference> differences = new ArrayList<>();
        if (metrics.counterUsedCount() != null
                && metrics.counterUsedCount() != metrics.usageLogAllowedAllTime()) {
            differences.add(new ReconciliationDifference(COUNTER_VS_USAGE_LOG_ALL, "COUNTER",
                    String.valueOf(metrics.usageLogAllowedAllTime()),
                    String.valueOf(metrics.counterUsedCount()),
                    "计数表余额口径与全量放行流水不一致"));
        }
        if (metrics.usageLogAllowedAllTime() != metrics.meteringFlowAllTime()) {
            differences.add(new ReconciliationDifference(USAGE_LOG_VS_METERING_ALL, "ALL_TIME",
                    String.valueOf(metrics.usageLogAllowedAllTime()),
                    String.valueOf(metrics.meteringFlowAllTime()),
                    "全量放行流水与全量计量流水不一致"));
        }
        if (metrics.usageLogAllowedPeriod() != metrics.meteringFlowPeriod()) {
            differences.add(new ReconciliationDifference(USAGE_LOG_VS_METERING_PERIOD, "PERIOD",
                    String.valueOf(metrics.usageLogAllowedPeriod()),
                    String.valueOf(metrics.meteringFlowPeriod()),
                    "本期放行流水与本期计量流水不一致"));
        }
        if (normal.pricingModel() == BillingPricingModel.PER_CALL) {
            final int quantity = normal.quantity() == null ? 0 : normal.quantity();
            if (quantity != metrics.meteringFlowPeriod()) {
                differences.add(new ReconciliationDifference(BILL_QUANTITY_VS_METERING,
                        "BILL_QUANTITY", String.valueOf(metrics.meteringFlowPeriod()),
                        String.valueOf(quantity), "按次账单数量与本期计量流水数不一致"));
            }
        }
        if (normal.amount().compareTo(recomputed) != 0) {
            differences.add(new ReconciliationDifference(AMOUNT_RECOMPUTE, "AMOUNT",
                    recomputed.toPlainString(), normal.amount().toPlainString(),
                    "账单金额与按快照复算金额不一致"));
        }
        differences.addAll(compareEventIds(normal, events));
        return differences;
    }

    /** 按次账单明细的计量流水 id 集合 vs 本期计量 id 集合（缺失 / 多出条目可定位）。 */
    private List<ReconciliationDifference> compareEventIds(final Bill normal,
            final List<MeteringEventRow> events) {
        if (normal.pricingModel() != BillingPricingModel.PER_CALL) {
            return List.of();
        }
        final Set<Long> billIds = new HashSet<>(detailRepository.findMeteringFlowIds(normal.billNo()));
        final Set<Long> eventIds = events.stream().map(MeteringEventRow::id)
                .collect(Collectors.toCollection(HashSet::new));
        final List<ReconciliationDifference> differences = new ArrayList<>();
        final List<Long> missing = eventIds.stream().filter(id -> !billIds.contains(id))
                .limit(MAX_DETAIL_ITEMS).toList();
        if (!missing.isEmpty()) {
            differences.add(new ReconciliationDifference(MISSING_IN_BILL, "BILL_DETAIL",
                    describe(eventIds.size()), describe(billIds.size()),
                    "账单缺失本期计量流水：" + missing));
        }
        final List<Long> extra = billIds.stream().filter(id -> !eventIds.contains(id))
                .limit(MAX_DETAIL_ITEMS).toList();
        if (!extra.isEmpty()) {
            differences.add(new ReconciliationDifference(EXTRA_IN_BILL, "BILL_DETAIL",
                    describe(eventIds.size()), describe(billIds.size()),
                    "账单多出非本期计量流水：" + extra));
        }
        return differences;
    }

    private void freezeBills(final String contractNo, final String period, final String operator,
            final LocalDateTime now) {
        for (final Bill bill : billRepository.findByContractAndPeriod(contractNo, period)) {
            if (bill.status() == BillStatus.FROZEN) {
                continue;
            }
            if (billRepository.markFrozen(bill.billNo(), bill.status()) > 0) {
                actionLogRepository.insert(new BillActionLog(bill.billNo(), BillingAction.FREEZE,
                        bill.status(), BillStatus.FROZEN, "对账差异冻结（fail-closed）", operator, now));
            }
        }
    }

    private void restoreBills(final String contractNo, final String period, final String operator,
            final LocalDateTime now) {
        for (final Bill bill : billRepository.findByContractAndPeriod(contractNo, period)) {
            if (bill.status() != BillStatus.FROZEN) {
                continue;
            }
            final BillStatus restored = bill.statusBeforeFreeze() == null
                    ? BillStatus.ISSUED : bill.statusBeforeFreeze();
            if (billRepository.markRestored(bill.billNo(), restored) > 0) {
                actionLogRepository.insert(new BillActionLog(bill.billNo(), BillingAction.UNFREEZE,
                        BillStatus.FROZEN, restored, "对账通过解冻（恢复原状态）", operator, now));
            }
        }
    }

    private static String writeDifferences(final List<ReconciliationDifference> differences) {
        if (differences.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(differences);
        } catch (final Exception e) {
            throw new IllegalStateException("对账差异明细序列化失败", e);
        }
    }

    private static String describe(final int value) {
        return String.valueOf(value);
    }

    private static void assertContractNo(final String contractNo) {
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw new BizException(com.ctds.common.errorcode.ErrorCodes.PARAM_INVALID,
                    "参数格式不合法");
        }
    }

    private static BizException notVisible() {
        return new BizException(BillingErrorCodes.BILL_NOT_VISIBLE,
                BillingErrorCodes.BILL_NOT_VISIBLE_MESSAGE);
    }
}
