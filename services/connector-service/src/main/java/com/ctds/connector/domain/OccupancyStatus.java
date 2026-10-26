package com.ctds.connector.domain;

/** 在途作业占用状态（hifi §2）：OCCUPY = 在途；RELEASED = 已释放（记录保留可查）。 */
public enum OccupancyStatus {
    OCCUPY,
    RELEASED;

    public static OccupancyStatus of(final String value) {
        for (final OccupancyStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知占用状态：" + value);
    }
}
