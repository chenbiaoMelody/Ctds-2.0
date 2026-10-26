package com.ctds.connector.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册申请 / 补正请求体（三要素；**不含 subjectNo**——身份一律取鉴权上下文，防越权自报）。
 * 校验文案为服务端常量（禁止拼接用户输入）。
 */
public record ConnectorRegisterRequest(
        @NotBlank(message = "连接器名称不能为空")
        @Size(min = 2, max = 64, message = "连接器名称长度须为 2~64 字符")
        String connectorName,
        @NotBlank(message = "连接器类型不能为空")
        String connectorType,
        @NotBlank(message = "连接器版本不能为空")
        @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$", message = "连接器版本格式须为主.次.修订")
        String connectorVersion) {
}
