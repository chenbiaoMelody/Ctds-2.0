package com.ctds.connector.domain;

/**
 * 连接器类型（V1.0 仅 STANDARD；轻量化连接器 C-6.4 = P1，非本卡）。
 */
public enum ConnectorType {
    STANDARD;

    /** 取值域校验（未知类型拒绝受理）。 */
    public static boolean supports(final String value) {
        return STANDARD.name().equals(value);
    }
}
