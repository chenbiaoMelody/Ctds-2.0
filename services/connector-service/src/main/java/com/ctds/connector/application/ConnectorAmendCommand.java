package com.ctds.connector.application;

/**
 * 补正命令（仅待核验态、仅属主；v1.0 只允许改名称 / 类型 / 版本）。
 */
public record ConnectorAmendCommand(
        String subjectNo,
        String applicationNo,
        String connectorName,
        String connectorType,
        String connectorVersion) {
}
