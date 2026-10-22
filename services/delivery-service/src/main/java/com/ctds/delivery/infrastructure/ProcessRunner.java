package com.ctds.delivery.infrastructure;

import java.time.Duration;
import java.util.List;

/**
 * 子进程执行端口（测试可替换为 Fake）。<b>真实实现只允许出现在 {@link DefaultProcessRunner}</b>：
 * argv 数组、不经 shell、逐命令超时；stdout 仅在显式捕获时读取（探活），其余一律丢弃。
 */
public interface ProcessRunner {

    /**
     * @param argv          独立参数数组（禁止拼接命令行字符串）
     * @param timeout       进程级超时（容器内 timeout 之外的 Java 侧兜底）
     * @param captureStdout 是否捕获 stdout（仅探活等小输出场景；作业执行不得捕获）
     */
    ProcessResult run(List<String> argv, Duration timeout, boolean captureStdout);

    /** 进程结果：超时 / 退出码 / 捕获到的 stdout（未捕获时为空串；内容不落日志）。 */
    record ProcessResult(int exitCode, boolean timedOut, String stdout) {
    }
}
