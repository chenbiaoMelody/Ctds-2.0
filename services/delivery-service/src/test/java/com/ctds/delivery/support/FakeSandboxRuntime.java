package com.ctds.delivery.support;

import com.ctds.delivery.domain.JobExecResult;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.domain.SandboxSpec;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 沙箱运行时测试替身（CI 主路径不依赖 Docker）：记录启动 / 停止 / 执行次数与最近命令。 */
public class FakeSandboxRuntime implements SandboxRuntime {

    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final List<SandboxSpec> launches = new CopyOnWriteArrayList<>();
    private final AtomicInteger stopCount = new AtomicInteger();
    private final AtomicInteger execCount = new AtomicInteger();
    private volatile List<String> lastCommand = List.of();
    private volatile Duration lastTimeout;
    private volatile JobExecResult nextResult = new JobExecResult(0, false, 12L);
    private volatile boolean launchFails;
    private volatile boolean unavailable;

    @Override
    public String launch(final SandboxSpec spec) {
        if (unavailable) {
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE,
                    SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE);
        }
        if (launchFails) {
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE);
        }
        launches.add(spec);
        running.add(spec.sandboxNo());
        return "ctds-sb-" + spec.sandboxNo();
    }

    @Override
    public void stop(final String sandboxNo) {
        stopCount.incrementAndGet();
        running.remove(sandboxNo);
    }

    @Override
    public boolean isRunning(final String sandboxNo) {
        return running.contains(sandboxNo);
    }

    @Override
    public JobExecResult exec(final String sandboxNo, final List<String> command,
            final Duration timeout) {
        execCount.incrementAndGet();
        lastCommand = List.copyOf(command);
        lastTimeout = timeout;
        return nextResult;
    }

    public void reset() {
        running.clear();
        launches.clear();
        stopCount.set(0);
        execCount.set(0);
        lastCommand = List.of();
        lastTimeout = null;
        nextResult = new JobExecResult(0, false, 12L);
        launchFails = false;
        unavailable = false;
    }

    public void setNextResult(final JobExecResult result) {
        this.nextResult = result;
    }

    public void setLaunchFails(final boolean value) {
        this.launchFails = value;
    }

    public void setUnavailable(final boolean value) {
        this.unavailable = value;
    }

    public List<SandboxSpec> launches() {
        return List.copyOf(launches);
    }

    public int stopCount() {
        return stopCount.get();
    }

    public int execCount() {
        return execCount.get();
    }

    public List<String> lastCommand() {
        return lastCommand;
    }

    /** 应用层实际传给运行时的作业超时（Java 侧 +10s 兜底由 DockerCliSandboxRuntime 内部叠加）。 */
    public Duration lastTimeout() {
        return lastTimeout;
    }
}
