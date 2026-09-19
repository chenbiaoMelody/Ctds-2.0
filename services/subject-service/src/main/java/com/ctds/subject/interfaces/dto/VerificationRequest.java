package com.ctds.subject.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 法人核验请求体（hifi 接口契约；身份证号仅用于本次核验，不回显——规格行为 3 第 1 条）。
 *
 * <p>DB-21 口径对齐（2026-09-19）：仅接受 18 位（GB 11643-1999，末位可为 X/x）。15 位旧号
 * 口径未引入，在接口参数校验层即拒绝（400）——与服务层 requireIdChecksum"非 18 位一律拒绝"
 * （DB-05）口径一致；此前注解放行 15 位形态属契约与服务层自相矛盾。</p>
 *
 * @param legalPersonName 法人姓名（须与证照识别的法定代表人一致）
 * @param legalPersonIdNo 法人身份证号（18 位，末位可为 X/x）
 */
public record VerificationRequest(
        @NotBlank(message = "法人姓名不能为空") @Size(max = 64, message = "法人姓名超长（最长 64 字符）")
        String legalPersonName,
        @NotBlank(message = "法人身份证号不能为空")
        @Pattern(regexp = "\\d{17}[0-9Xx]", message = "身份证号格式不正确（须为 18 位，末位可为 X）")
        String legalPersonIdNo) {
}
