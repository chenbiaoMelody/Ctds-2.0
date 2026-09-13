package com.ctds.subject.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证照核对确认请求体（hifi 接口契约；确认值与 OCR 识别值比对，信用代码不一致阻断——规格行为 2 第 4 条）。
 *
 * @param subjectName 确认的主体名称
 * @param uscc        确认的统一社会信用代码
 * @param legalPerson 确认的法定代表人姓名
 * @param regAddress  确认的注册地址
 */
public record ConfirmationRequest(
        @NotBlank(message = "主体名称不能为空") @Size(max = 128, message = "主体名称超长（最长 128 字符）")
        String subjectName,
        @NotBlank(message = "统一社会信用代码不能为空") @Size(max = 18, message = "统一社会信用代码超长（最长 18 字符）")
        String uscc,
        @NotBlank(message = "法定代表人不能为空") @Size(max = 64, message = "法定代表人超长（最长 64 字符）")
        String legalPerson,
        @NotBlank(message = "注册地址不能为空") @Size(max = 256, message = "注册地址超长（最长 256 字符）")
        String regAddress) {
}
