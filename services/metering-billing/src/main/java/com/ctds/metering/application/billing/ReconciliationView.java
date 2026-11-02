package com.ctds.metering.application.billing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账结论出站视图（hifi §3.4 B-W4/B-R4）：四方数值 + 账单合计 + 复算金额 + 结论 + 差异明细，
 * 参与方与运营方均按此视图读取（差异条目可定位到腿 / 期望值 / 实际值）。
 */
public record ReconciliationView(
        long id,
        String contractNo,
        String period,
        int counterUsedCount,
        int usageLogAllowedCount,
        int meteringFlowCount,
        int usageLogAllowedAllTime,
        int meteringFlowAllTime,
        Integer billQuantity,
        String billTotalAmount,
        String recomputedAmount,
        String conclusion,
        List<ReconciliationDifference> differences,
        String createdBy,
        LocalDateTime createdAt) {

    /** 差异明细条目（type = 判定项；leg = 数据腿；expected/actual 为字符串以便业务阅读）。 */
    public record ReconciliationDifference(
            String type,
            String leg,
            String expected,
            String actual,
            String detail) {
    }
}
