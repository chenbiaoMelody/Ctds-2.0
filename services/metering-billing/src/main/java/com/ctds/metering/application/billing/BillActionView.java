package com.ctds.metering.application.billing;

import java.time.LocalDateTime;

/**
 * 账单动作时间线出站视图（hifi §3.4）：创建 / 确认 / 冻结 / 解冻与业务可读原因。
 */
public record BillActionView(
        String action,
        String fromStatus,
        String toStatus,
        String reason,
        String operatorSubjectNo,
        LocalDateTime createdAt) {
}
