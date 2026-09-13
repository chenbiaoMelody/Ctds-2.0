package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;

/**
 * 政务 CA 证书提交验证结果（WBS-3.1.4 hifi 接口契约；业务不通过是结论非异常，与法人核验 FAIL 同构）。
 *
 * @param subjectNo  申请编号
 * @param conclusion 渠道结论：PASS / FAIL
 * @param status     验证后主体状态：通过 = PENDING_REVIEW，不通过保持 PENDING_CERT
 * @param failReason 不通过原因（业务可读；通过时为 null）
 */
public record GovCaCertificationResult(String subjectNo, String conclusion, SubjectStatus status,
        String failReason) {
}
