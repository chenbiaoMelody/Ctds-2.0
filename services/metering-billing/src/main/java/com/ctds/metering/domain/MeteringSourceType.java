package com.ctds.metering.domain;

/**
 * 计量事件源类型（B7-1 四源；hifi §2 值域封闭枚举）。
 * API 调用计量源唯一 = 网关事件（GATEWAY_API）——通道 2 采集排除 delivery_type=API 行防双计（B7-3）。
 */
public enum MeteringSourceType {

    GATEWAY_API("gateway.delivery.metering"),
    FILE_DELIVERY("delivery.file"),
    SANDBOX_EXPORT("delivery.sandbox_export"),
    SANDBOX_JOB("sandbox.job");

    private final String defaultAction;

    MeteringSourceType(final String defaultAction) {
        this.defaultAction = defaultAction;
    }

    /** 采集常量映射的动作名（hifi §2——流水 action 列取值，服务端常量不拼用户输入）。 */
    public String defaultAction() {
        return defaultAction;
    }
}
