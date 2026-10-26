package com.ctds.connector.domain;

/**
 * 绑定记录状态：ACTIVE = 生效；RELEASED = 已解除（归 3.6.4 注销，本卡只写 ACTIVE）。
 */
public enum BindingStatus {
    ACTIVE,
    RELEASED;

    public static BindingStatus of(final String value) {
        for (final BindingStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知绑定状态：" + value);
    }
}
