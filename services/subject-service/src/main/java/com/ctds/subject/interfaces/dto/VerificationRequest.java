package com.ctds.subject.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 法人核验请求体（hifi 接口契约；身份证号仅用于本次核验，不回显——规格行为 3 第 1 条）。
 *
 * @param legalPersonName 法人姓名（须与证照识别的法定代表人一致）
 * @param legalPersonIdNo 法人身份证号（15 或 18 位）
 */
public record VerificationRequest(
        @NotBlank(message = "法人姓名不能为空") @Size(max = 64, message = "法人姓名超长（最长 64 字符）")
        String legalPersonName,
        @NotBlank(message = "法人身份证号不能为空")
        @Pattern(regexp = "(\\d{14}[0-9Xx]|\\d{17}[0-9Xx])", message = "身份证号格式不正确")
        String legalPersonIdNo) {
}
