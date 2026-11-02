package com.ctds.metering.application.billing;

import com.ctds.common.errorcode.BizException;
import com.ctds.metering.config.BillingProperties;
import com.ctds.metering.domain.billing.BillingContractReader;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import com.ctds.metering.domain.billing.BillingPricingModel;
import com.ctds.metering.domain.billing.BillingUsageReader;
import com.ctds.metering.domain.billing.ContractPricingSnapshot;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 自然月自动出账（hifi §4，B10-1 周期腿）：每月 1 日 00:10（cron / enabled 可配）对上一个自然月
 * 扫描可出账合约并逐合约独立事务出账。按次无事件 / 免费档 / 交易额分成无基数登记均跳过
 * （Q3-A 诚实边界：分成档需运营人工补出账）；重复出账由唯一键幂等跳过（多实例安全）。
 */
@Service
public class BillingAutoService {

    private static final Logger log = LoggerFactory.getLogger(BillingAutoService.class);

    private final BillingContractReader contractReader;
    private final BillingUsageReader usageReader;
    private final BillingOutbillService outbillService;
    private final BillingProperties properties;
    private final Clock clock;

    public BillingAutoService(final BillingContractReader contractReader,
            final BillingUsageReader usageReader,
            final BillingOutbillService outbillService,
            final BillingProperties properties,
            final Clock clock) {
        this.contractReader = contractReader;
        this.usageReader = usageReader;
        this.outbillService = outbillService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 调度入口：默认每月 1 日 00:10 出上月账（ctds.billing.auto.cron / enabled 可配）。 */
    @Scheduled(cron = "${ctds.billing.auto.cron:0 10 0 1 * ?}")
    public void scheduledOutbill() {
        if (!properties.getAuto().isEnabled()) {
            return;
        }
        final YearMonth target = YearMonth.now(clock).minusMonths(1);
        final AutoOutbillResult result = runForPeriod(target);
        log.info("自动出账完成 period={} scanned={} billed={} duplicate={} noMetering={} noBase={} failed={}",
                BillingPeriods.text(target), result.scanned(), result.billed(),
                result.skippedDuplicate(), result.skippedNoMetering(), result.skippedNoBase(),
                result.failed());
    }

    /** 对指定周期批量出账（测试直接调用；生产由调度触发）。 */
    public AutoOutbillResult runForPeriod(final YearMonth month) {
        final String periodText = BillingPeriods.text(month);
        final List<ContractPricingSnapshot> contracts = contractReader.findBillable(
                BillingPeriods.start(month), BillingPeriods.end(month));
        int billed = 0;
        int skippedDuplicate = 0;
        int skippedNoMetering = 0;
        int skippedNoBase = 0;
        int failed = 0;
        for (final ContractPricingSnapshot contract : contracts) {
            if (contract.pricingModel() == BillingPricingModel.FREE) {
                continue;
            }
            if (contract.pricingModel() == BillingPricingModel.REVENUE_SHARE) {
                // 分成档无基数登记：跳过并告警，需运营人工出账（Q3-A）
                skippedNoBase++;
                log.warn("自动出账跳过交易额分成合约（无约定交易额登记）contractNo={} period={}",
                        contract.contractNo(), periodText);
                continue;
            }
            if (contract.pricingModel() == BillingPricingModel.PER_CALL
                    && usageReader.events(contract.contractNo(), BillingPeriods.start(month),
                            BillingPeriods.end(month)).isEmpty()) {
                skippedNoMetering++;
                continue;
            }
            try {
                outbillService.autoOutbill(contract.contractNo(), periodText);
                billed++;
            } catch (final BizException e) {
                if (BillingErrorCodes.BILL_DUPLICATED.value().equals(e.getErrorCode().value())) {
                    skippedDuplicate++;
                } else {
                    failed++;
                    log.warn("自动出账跳过合约 contractNo={} period={} 原因={}",
                            contract.contractNo(), periodText, e.getErrorCode().value());
                }
            } catch (final RuntimeException e) {
                failed++;
                log.error("自动出账失败 contractNo={} period={}", contract.contractNo(), periodText, e);
            }
        }
        return new AutoOutbillResult(contracts.size(), billed, skippedDuplicate, skippedNoMetering,
                skippedNoBase, failed);
    }

    /** 批量出账结果（仅日志留痕，无对外端点，hifi §4）。 */
    public record AutoOutbillResult(
            int scanned,
            int billed,
            int skippedDuplicate,
            int skippedNoMetering,
            int skippedNoBase,
            int failed) {
    }
}
