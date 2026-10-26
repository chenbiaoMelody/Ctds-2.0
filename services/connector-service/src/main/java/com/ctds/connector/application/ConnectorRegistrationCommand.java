package com.ctds.connector.application;

/**
 * 注册申请命令（subjectNo 由服务端从鉴权上下文取，请求体不含）。
 */
public record ConnectorRegistrationCommand(
        String subjectNo,
        String connectorName,
        String connectorType,
        String connectorVersion) {
}
