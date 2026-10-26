package com.ctds.connector.domain;

/**
 * 连接器档案状态（规格 §7 Q5-A 三主态）。
 * 本卡只实现 PENDING_REVIEW → REGISTERED；CANCELLED 为 3.6.4 注销预留（建表取值已定义）。
 */
public enum ConnectorStatus {
    PENDING_REVIEW,
    REGISTERED,
    CANCELLED;

    /** 库值 → 枚举（未知值即数据异常，不静默降级）。 */
    public static ConnectorStatus of(final String value) {
        for (final ConnectorStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知连接器状态：" + value);
    }
}
