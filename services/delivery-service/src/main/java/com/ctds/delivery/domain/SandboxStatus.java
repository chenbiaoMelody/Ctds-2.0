package com.ctds.delivery.domain;

/** 沙箱任务生命周期状态（hifi §3.2）：开通即 RUNNING；STOPPED / EXPIRED 后不可再使用。 */
public enum SandboxStatus {
    RUNNING,
    STOPPED,
    EXPIRED;

    public boolean usable() {
        return this == RUNNING;
    }
}
