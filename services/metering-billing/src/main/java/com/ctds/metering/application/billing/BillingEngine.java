package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.metering.domain.billing.BillDetailLine;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import com.ctds.metering.domain.billing.BillingPricingModel;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import com.ctds.metering.domain.billing.MeteringEventRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 计费引擎（hifi §3.2）：三模式计算 + 快照取值 + HALF_UP 两位舍入。
 * 全部输入来自合约快照或出账参数，输出金额与明细可被第三方按同一快照复算（B9-4 同输入同输出）；
 * 免费档不出账（B8-2）、按次无事件不出空单、付费档价格缺失 fail-closed。
 */
@Service
public class BillingEngine {

    /** 分成费率百分比口径（price_amount = 0~100）。 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** DECIMAL(12,2) 可表示的最大金额。 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private static final int SCALE = 2;

    /**
     * 按合约快照与本期计量事件计算账单金额与明细。
     *
     * @param contract 合约计费快照（双方 / 产品 / 定价档 / 定价数值）
     * @param periodEvents 本期计量事件（按 occurred_at 归属的自然月；非按次档可为空）
     * @param agreedTransactionAmount 出账参数登记的约定交易额（仅分成档必填，Q3-A）
     */
    public BillComputation compute(final ContractPricingSnapshot contract,
            final List<MeteringEventRow> periodEvents,
            final BigDecimal agreedTransactionAmount) {
        final BillingPricingModel model = contract.pricingModel();
        if (model == null) {
            throw notBillable();
        }
        return switch (model) {
            case FREE -> throw notBillable();
            case PER_CALL -> perCall(contract, periodEvents);
            case MONTHLY -> monthly(contract);
            case REVENUE_SHARE -> revenueShare(contract, agreedTransactionAmount);
        };
    }

    private BillComputation perCall(final ContractPricingSnapshot contract,
            final List<MeteringEventRow> periodEvents) {
        final BigDecimal unitPrice = requirePrice(contract);
        if (periodEvents.isEmpty()) {
            // 该周期无计量：不出空账单（hifi §3.2）
            throw notBillable();
        }
        final int quantity = periodEvents.size();
        final BigDecimal amount = scale(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        assertAmount(amount);
        final List<ComputedLine> lines = new ArrayList<>();
        for (final MeteringEventRow event : periodEvents) {
            lines.add(new ComputedLine(BillDetailLine.LINE_PER_CALL_EVENT,
                    "按次计量事件（" + event.sourceType() + "）", event.id(), event.sourceType(),
                    event.sourceNo(), event.occurredAt(), 1, unitPrice, scale(unitPrice)));
        }
        return new BillComputation(quantity, unitPrice, null, null, amount, lines);
    }

    private BillComputation monthly(final ContractPricingSnapshot contract) {
        final BigDecimal unitPrice = requirePrice(contract);
        final BigDecimal amount = scale(unitPrice);
        assertAmount(amount);
        // 包月 = 订单周期费整月，与调用次数无关（B9-2 / Q5-A）
        return new BillComputation(null, unitPrice, null, null, amount,
                List.of(new ComputedLine(BillDetailLine.LINE_PERIOD_FEE, "包月周期费（整月，不按天折算）",
                        null, null, null, null, 1, unitPrice, amount)));
    }

    private BillComputation revenueShare(final ContractPricingSnapshot contract,
            final BigDecimal agreedTransactionAmount) {
        final BigDecimal shareRate = requirePrice(contract);
        if (shareRate.compareTo(BigDecimal.ZERO) < 0 || shareRate.compareTo(HUNDRED) > 0) {
            throw notBillable();
        }
        final BigDecimal baseAmount = requireBase(agreedTransactionAmount);
        final BigDecimal amount = baseAmount.multiply(shareRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        assertAmount(amount);
        return new BillComputation(null, null, baseAmount, shareRate, amount,
                List.of(new ComputedLine(BillDetailLine.LINE_REVENUE_SHARE_BASE,
                        "交易额分成（约定交易额 × 费率）", null, null, null, null, 1, null, amount)));
    }

    /** 付费档定价数值必须存在（数据异常 → 不满足出账条件 fail-closed，hifi §3.2）。 */
    private BigDecimal requirePrice(final ContractPricingSnapshot contract) {
        final BigDecimal price = contract.priceAmount();
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw notBillable();
        }
        return price;
    }

    /** 分成档约定交易额（出账参数）：必填、> 0、小数位 ≤ 2（Q3-A / hifi §3.2）。 */
    private BigDecimal requireBase(final BigDecimal agreedTransactionAmount) {
        if (agreedTransactionAmount == null || agreedTransactionAmount.compareTo(BigDecimal.ZERO) <= 0
                || agreedTransactionAmount.scale() > SCALE) {
            throw new BizException(BillingErrorCodes.BILL_SHARE_BASE_INVALID,
                    BillingErrorCodes.BILL_SHARE_BASE_INVALID_MESSAGE);
        }
        return agreedTransactionAmount;
    }

    private static BigDecimal scale(final BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static void assertAmount(final BigDecimal amount) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            // 金额超出字段精度：数据 / 参数异常（对外 1000C0001，不外泄内部细节）
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
    }

    private static BizException notBillable() {
        return new BizException(BillingErrorCodes.CONTRACT_NOT_BILLABLE,
                BillingErrorCodes.CONTRACT_NOT_BILLABLE_MESSAGE);
    }

    /** 计费结果：账单头金额构成 + 待装配明细行（billNo / lineNo 由出账服务生成）。 */
    public record BillComputation(
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal baseAmount,
            BigDecimal shareRate,
            BigDecimal amount,
            List<ComputedLine> lines) {
    }

    /** 待装配明细行（与 billing_bill_detail 列面一一对应，除 billNo / lineNo / id）。 */
    public record ComputedLine(
            String lineType,
            String description,
            Long meteringFlowId,
            String sourceType,
            String sourceNo,
            LocalDateTime occurredAt,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal amount) {
    }
}
