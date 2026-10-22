package com.ctds.delivery.domain;

import java.util.List;

/**
 * 沙箱容器运行时端口（hifi §4.1）。实现侧只允许经配置化 argv 调用 docker CLI，
 * 不拼接用户输入、不经 shell；运行时关闭时一律 fail-closed（1009S0004）。
 */
public interface SandboxRuntime {

    /**
     * 按安全基线启动容器。
     *
     * @return 容器句柄（容器名，由 sandboxNo 确定；容器 ID 不落库、不落日志）
     */
    String launch(SandboxSpec spec);

    /** 停止并移除容器（幂等；容器不存在视为成功）。 */
    void stop(String sandboxNo);

    /** 探活：容器不存在返回 false。 */
    boolean isRunning(String sandboxNo);

    /** 在容器内执行受控作业命令（固定 argv，容器内 timeout 限时）。 */
    JobExecResult exec(String sandboxNo, List<String> command, java.time.Duration timeout);
}
