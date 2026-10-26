package com.ctds.connector.domain;

/**
 * 版本变更记录状态（hifi §2）：PENDING_CONFIRM = 不兼容升级待运营方显式确认；APPLIED = 已生效；
 * VOIDED = 连接器注销时作废（不生效）。本枚举不是连接器主状态（主状态仍只有三主态）。
 */
public enum VersionChangeStatus {
    PENDING_CONFIRM,
    APPLIED,
    VOIDED;

    /** 库值 → 枚举（未知值即数据异常，不静默降级）。 */
    public static VersionChangeStatus of(final String value) {
        for (final VersionChangeStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知版本变更状态：" + value);
    }
}
