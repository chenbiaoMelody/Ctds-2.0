package com.ctds.subject.interfaces.dto;

import com.ctds.subject.application.RegisterCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体（hifi 接口契约；校验注解逐字段提示，业务可读中文文案，
 * 格式规则与应用层 requireValid 双层一致——规格行为 1 第 1 条）。
 *
 * @param subjectName  主体名称
 * @param uscc         统一社会信用代码（18 位，GB 32100-2015 字符集）
 * @param subjectType  主体类型：ENTERPRISE 企业 / INSTITUTION 机构 / GOV 政府部门
 * @param regAddress   注册地址
 * @param contactName  联系人姓名
 * @param contactPhone 联系电话
 * @param adminAccount 管理员账号
 */
public record RegisterRequest(
        @NotBlank(message = "主体名称不能为空") @Size(max = 128, message = "主体名称超长（最长 128 字符）")
        String subjectName,
        @NotBlank(message = "统一社会信用代码不能为空")
        @Pattern(regexp = "[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}", message = "统一社会信用代码格式不正确")
        String uscc,
        @NotBlank(message = "主体类型不能为空") @Pattern(regexp = "ENTERPRISE|INSTITUTION|GOV", message = "主体类型不合法")
        String subjectType,
        @NotBlank(message = "注册地址不能为空") @Size(max = 256, message = "注册地址超长（最长 256 字符）")
        String regAddress,
        @NotBlank(message = "联系人姓名不能为空") @Size(max = 64, message = "联系人姓名超长（最长 64 字符）")
        String contactName,
        @NotBlank(message = "联系电话不能为空") @Size(max = 32, message = "联系电话超长（最长 32 字符）")
        String contactPhone,
        @NotBlank(message = "管理员账号不能为空")
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "管理员账号不合法（仅允许字母数字 . _ -）")
        String adminAccount) {

    /** 转应用层命令（interfaces → application 单向依赖）。 */
    public RegisterCommand toCommand() {
        return new RegisterCommand(subjectName, uscc, subjectType, regAddress,
                contactName, contactPhone, adminAccount);
    }
}
