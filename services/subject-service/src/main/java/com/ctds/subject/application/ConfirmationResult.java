package com.ctds.subject.application;

/**
 * 核对确认结果（hifi 接口契约）。
 *
 * @param subjectNo 申请编号
 * @param confirmed 是否确认生效
 * @param nextStep  下一步标识（LEGAL_PERSON_VERIFICATION 法人核验）
 */
public record ConfirmationResult(String subjectNo, boolean confirmed, String nextStep) {
}
