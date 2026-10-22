package com.ctds.delivery.domain;

/** 受控作业状态（hifi §2.2）：一次作业 = 一次数据使用；执行结论只落状态与退出码。 */
public enum SandboxJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMEOUT
}
