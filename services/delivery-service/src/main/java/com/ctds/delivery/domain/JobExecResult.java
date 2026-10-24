package com.ctds.delivery.domain;

/**
 * 受控作业执行结果（hifi §4.3）：只保留退出码、是否超时与耗时；
 * <b>stdout / stderr 一律丢弃</b>（可能含数据，禁止落库 / 落审计 / 落日志）。
 */
public record JobExecResult(int exitCode, boolean timedOut, long durationMs) {

    /** 容器内 timeout 命令的固定超时退出码。 */
    public static final int TIMEOUT_EXIT_CODE = 124;

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
