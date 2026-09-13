package com.ctds.subject.application;

/**
 * 法人核验结果（hifi 接口契约）。
 *
 * @param subjectNo              申请编号
 * @param conclusion             结论：PASS / FAIL（渠道技术异常直接抛出，不进入本结果）
 * @param status                 结论后主体状态（通过 = 待审核自动流转）
 * @param failReason             不通过原因（通过为 null）
 * @param remainingAttemptsToday 当日剩余可尝试次数
 */
public record VerificationResult(String subjectNo, String conclusion, String status, String failReason,
        int remainingAttemptsToday) {
}
