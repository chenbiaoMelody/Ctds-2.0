package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.metering.domain.billing.Bill;
import com.ctds.metering.domain.billing.BillActionLog;
import com.ctds.metering.domain.billing.BillActionLogRepository;
import com.ctds.metering.domain.billing.BillDetailLine;
import com.ctds.metering.domain.billing.BillDetailRepository;
import com.ctds.metering.domain.billing.BillNoGenerator;
import com.ctds.metering.domain.billing.BillRepository;
import com.ctds.metering.domain.billing.BillSearchCriteria;
import com.ctds.metering.domain.billing.BillStatus;
import com.ctds.metering.domain.billing.BillType;
import com.ctds.metering.domain.billing.BillingAction;
import com.ctds.metering.domain.billing.BillingContractReader;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import com.ctds.metering.domain.billing.Reconciliation;
import com.ctds.metering.domain.billing.ReconciliationRepository;
import com.ctds.metering.application.billing.ReconciliationView.ReconciliationDifference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计费读面与账单动作（hifi §1 B-R1~B-R3 / B-W2 / B-W3）：
 * 本方读面按合约参与方硬校验（非本方与不存在同码同文 1011C0009 防枚举）；
 * 确认仅 ISSUED（冻结 1011C0011、其余状态 1011C0012）；更正 = 另出更正单，原单零改动（B10-3）。
 */
@Service
public class BillingQueryService {

    private static final Logger log = LoggerFactory.getLogger(BillingQueryService.class);
    /** 动作留痕 reason 列宽（VARCHAR(512)）：出站原因按**拼装后总长**校验，非仅用户输入长度。 */
    private static final int MAX_REASON_LENGTH = 512;
    /** 更正单留痕原因前缀形态（"更正单（原单 {billNo}）："）。 */
    private static final String CORRECTION_REASON_PREFIX = "更正单（原单 ";
    private static final String CORRECTION_REASON_SUFFIX = "）：";
    /** 账单号形态（BL+6 位，取号器口径；hifi §1 格式非法 → 1000C0001）。 */
    private static final Pattern BILL_NO = Pattern.compile("^BL\\d{6}$");
    /** 合约号形态（CO+6 位，沿计量读面同口径）。 */
    private static final Pattern CONTRACT_NO = Pattern.compile("^[A-Z]{2}\\d{6}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BillRepository billRepository;
    private final BillDetailRepository detailRepository;
    private final BillActionLogRepository actionLogRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final BillingContractReader contractReader;
    private final BillNoGenerator billNoGenerator;
    private final Clock clock;

    public BillingQueryService(final BillRepository billRepository,
            final BillDetailRepository detailRepository,
            final BillActionLogRepository actionLogRepository,
            final ReconciliationRepository reconciliationRepository,
            final BillingContractReader contractReader,
            final BillNoGenerator billNoGenerator,
            final Clock clock) {
        this.billRepository = billRepository;
        this.detailRepository = detailRepository;
        this.actionLogRepository = actionLogRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.contractReader = contractReader;
        this.billNoGenerator = billNoGenerator;
        this.clock = clock;
    }

    /** B-R1 本方账单分页（当前主体为需方或供方；myRole 标注；B12-1）。 */
    public PageResult<BillSummary> mine(final String subject, final BillSearchCriteria criteria,
            final PageQuery page) {
        if (subject == null) {
            throw notVisible();
        }
        final List<BillSummary> items = new ArrayList<>();
        for (final Bill bill : billRepository.findMine(subject, criteria, page)) {
            items.add(toSummary(bill, myRole(bill, subject)));
        }
        return PageResult.of(items, billRepository.countMine(subject, criteria), page);
    }

    /** B-R3 运营全量检索（B12-2）。 */
    public PageResult<BillSummary> governance(final BillSearchCriteria criteria, final PageQuery page) {
        final List<BillSummary> items = billRepository.findGovernance(criteria, page).stream()
                .map(bill -> toSummary(bill, null)).toList();
        return PageResult.of(items, billRepository.countGovernance(criteria), page);
    }

    /** B-R2 账单详情（本方或运营；非本方与不存在同码同文 1011C0009）。 */
    public BillDetailView detail(final String billNo, final String subject, final boolean admin) {
        final Bill bill = loadVisibleBill(billNo, subject, admin);
        return toDetailView(bill);
    }

    /** B-W2 运营确认（ISSUED → CONFIRMED；冻结被拒 1011C0011）。 */
    @Transactional
    public BillDetailView confirm(final String billNo, final String operator) {
        final Bill bill = loadBill(billNo);
        if (bill.status() == BillStatus.FROZEN) {
            throw new BizException(BillingErrorCodes.BILL_FROZEN, BillingErrorCodes.BILL_FROZEN_MESSAGE);
        }
        if (bill.status() != BillStatus.ISSUED) {
            throw new BizException(BillingErrorCodes.BILL_STATUS_CONFLICT,
                    BillingErrorCodes.BILL_STATUS_CONFLICT_MESSAGE);
        }
        if (billRepository.markConfirmed(billNo) == 0) {
            throw new BizException(BillingErrorCodes.BILL_STATUS_CONFLICT,
                    BillingErrorCodes.BILL_STATUS_CONFLICT_MESSAGE);
        }
        actionLogRepository.insert(new BillActionLog(billNo, BillingAction.CONFIRM, BillStatus.ISSUED,
                BillStatus.CONFIRMED, "运营确认账单", operator, LocalDateTime.now(clock)));
        return toDetailView(loadBill(billNo));
    }

    /** B-W3 更正单（红字冲正 / 补差）：原单金额与明细逐字不变，更正单独立编号、双向可溯。 */
    @Transactional
    public BillDetailView correction(final String originalBillNo, final BigDecimal amount,
            final String reason, final String operator) {
        final Bill original = loadBill(originalBillNo);
        if (original.status() == BillStatus.FROZEN) {
            throw new BizException(BillingErrorCodes.BILL_FROZEN, BillingErrorCodes.BILL_FROZEN_MESSAGE);
        }
        if (original.billType() == BillType.CORRECTION) {
            throw new BizException(BillingErrorCodes.BILL_STATUS_CONFLICT,
                    BillingErrorCodes.BILL_STATUS_CONFLICT_MESSAGE);
        }
        assertCorrectionAmount(amount);
        final String actionReason = correctionReason(originalBillNo, reason);
        final LocalDateTime now = LocalDateTime.now(clock);
        final String billNo = billNoGenerator.nextBillNo();
        final Bill correction = new Bill(0L, billNo, BillType.CORRECTION, original.contractNo(),
                original.requesterNo(), original.providerNo(), original.productId(),
                original.productName(), original.period(), original.pricingModel(), null, null,
                null, null, amount, BillStatus.ISSUED, null, originalBillNo, operator, now);
        billRepository.insert(correction);
        actionLogRepository.insert(new BillActionLog(billNo, BillingAction.CREATE, null,
                BillStatus.ISSUED, actionReason, operator, now));
        return toDetailView(correction);
    }

    /** B-R4 本方合约对账结论分页（参与方或运营；合约不存在 1011C0014、非本方 1011C0009）。 */
    public PageResult<ReconciliationView> reconciliations(final String contractNo, final String period,
            final String subject, final boolean admin, final PageQuery page) {
        if (contractNo == null || !CONTRACT_NO.matcher(contractNo).matches()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        final ContractPricingSnapshot contract = contractReader.findByContractNo(contractNo)
                .orElseThrow(() -> new BizException(BillingErrorCodes.CONTRACT_NOT_FOUND,
                        BillingErrorCodes.CONTRACT_NOT_FOUND_MESSAGE));
        if (!admin && (subject == null || (!subject.equals(contract.requesterNo())
                && !subject.equals(contract.providerNo())))) {
            throw notVisible();
        }
        final List<ReconciliationView> items = reconciliationRepository.find(contractNo, period, page)
                .stream().map(BillingQueryService::toReconciliationView).toList();
        return PageResult.of(items, reconciliationRepository.count(contractNo, period), page);
    }

    private Bill loadVisibleBill(final String billNo, final String subject, final boolean admin) {
        final Bill bill = loadBill(billNo);
        if (!admin && (subject == null || (!subject.equals(bill.requesterNo())
                && !subject.equals(bill.providerNo())))) {
            throw notVisible();
        }
        return bill;
    }

    private Bill loadBill(final String billNo) {
        if (billNo == null || !BILL_NO.matcher(billNo).matches()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        return billRepository.findByBillNo(billNo).orElseThrow(BillingQueryService::notVisible);
    }

    private BillDetailView toDetailView(final Bill bill) {
        final List<BillDetailLineView> details = detailRepository.findByBillNo(bill.billNo()).stream()
                .map(BillingQueryService::toLineView).toList();
        final List<BillActionView> actions = actionLogRepository.findByBillNo(bill.billNo()).stream()
                .map(BillingQueryService::toActionView).toList();
        final List<String> correctionBillNos = bill.billType() == BillType.NORMAL
                ? billRepository.findCorrectionsOf(bill.billNo()).stream().map(Bill::billNo).toList()
                : List.of();
        return new BillDetailView(bill.billNo(), bill.billType().name(), bill.contractNo(),
                bill.requesterNo(), bill.providerNo(), bill.productId(), bill.productName(),
                bill.period(), bill.pricingModel().name(), bill.quantity(),
                text(bill.unitPrice()), text(bill.baseAmount()), text(bill.shareRate()),
                text(bill.amount()), bill.status().name(), bill.correctionOfBillNo(),
                bill.createdAt(), details, actions, correctionBillNos);
    }

    private static BillSummary toSummary(final Bill bill, final String myRole) {
        return new BillSummary(bill.billNo(), bill.billType().name(), bill.contractNo(),
                bill.requesterNo(), bill.providerNo(), bill.productId(), bill.productName(),
                bill.period(), bill.pricingModel().name(), text(bill.amount()), bill.status().name(),
                bill.correctionOfBillNo(), bill.createdAt(), myRole);
    }

    private static BillDetailLineView toLineView(final BillDetailLine line) {
        return new BillDetailLineView(line.lineNo(), line.lineType(), line.description(),
                line.meteringFlowId(), line.sourceType(), line.sourceNo(), line.occurredAt(),
                line.quantity(), text(line.unitPrice()), text(line.amount()));
    }

    private static BillActionView toActionView(final BillActionLog actionLog) {
        return new BillActionView(actionLog.action().name(),
                actionLog.fromStatus() == null ? null : actionLog.fromStatus().name(),
                actionLog.toStatus() == null ? null : actionLog.toStatus().name(),
                actionLog.reason(), actionLog.operatorSubjectNo(), actionLog.createdAt());
    }

    private static ReconciliationView toReconciliationView(final Reconciliation reconciliation) {
        return new ReconciliationView(reconciliation.id(), reconciliation.contractNo(),
                reconciliation.period(), reconciliation.counterUsedCount(),
                reconciliation.usageLogAllowedCount(), reconciliation.meteringFlowCount(),
                reconciliation.usageLogAllowedAllTime(), reconciliation.meteringFlowAllTime(),
                reconciliation.billQuantity(), text(reconciliation.billTotalAmount()),
                text(reconciliation.recomputedAmount()), reconciliation.conclusion().name(),
                parseDifferences(reconciliation.detailsJson()), reconciliation.createdBy(),
                reconciliation.createdAt());
    }

    /** 差异明细 JSON 反序列化（对账记录写入端为受控结构；解析失败按无明细处理并告警）。 */
    private static List<ReconciliationDifference> parseDifferences(final String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(detailsJson, new TypeReference<List<ReconciliationDifference>>() { });
        } catch (final Exception e) {
            log.warn("对账差异明细解析失败（按空明细出站）", e);
            return List.of();
        }
    }

    private static String myRole(final Bill bill, final String subject) {
        if (subject != null && subject.equals(bill.requesterNo())) {
            return "REQUESTER";
        }
        if (subject != null && subject.equals(bill.providerNo())) {
            return "PROVIDER";
        }
        return null;
    }

    private static void assertCorrectionAmount(final BigDecimal amount) {
        if (amount == null || amount.signum() == 0 || amount.scale() > 2
                || amount.abs().compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
    }

    /**
     * 更正留痕原因：按"前缀 + 原单号 + 用户输入"的**拼装后总长**校验列宽（VARCHAR(512)），
     * 超限 → 1000C0001（不外泄内部细节；不落库、不截断）。
     */
    private static String correctionReason(final String originalBillNo, final String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        final String assembled = CORRECTION_REASON_PREFIX + originalBillNo + CORRECTION_REASON_SUFFIX
                + reason.trim();
        if (assembled.length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        return assembled;
    }

    private static String text(final BigDecimal value) {
        return Optional.ofNullable(value).map(BigDecimal::toPlainString).orElse(null);
    }

    private static BizException notVisible() {
        return new BizException(BillingErrorCodes.BILL_NOT_VISIBLE,
                BillingErrorCodes.BILL_NOT_VISIBLE_MESSAGE);
    }
}
