package com.ctds.delivery.infrastructure;

import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.JobExecResult;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.domain.SandboxSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Docker CLI 运行时适配器（hifi §4，Q2-A）：零新依赖；命令全部经 {@link DockerCommandBuilder}
 * 构造为 argv 数组；运行时关闭 / 环境异常一律 fail-closed。
 *
 * <p>留痕口径：容器名 / ID / 镜像 / 命令一律不入库、不入审计、不入日志——
 * 本类日志只记录 sandboxNo 与结论。</p>
 */
@Component
public class DockerCliSandboxRuntime implements SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerCliSandboxRuntime.class);
    /** Java 侧兜底超时余量（容器内 timeout 负责杀进程；hifi §4.3）。 */
    private static final long EXEC_GRACE_SECONDS = 10L;

    private final SandboxProperties properties;
    private final ProcessRunner processRunner;

    public DockerCliSandboxRuntime(final SandboxProperties properties,
            final ProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    @Override
    public String launch(final SandboxSpec spec) {
        requireEnabled();
        verifyPaths(spec);
        final ProcessRunner.ProcessResult result = run(
                DockerCommandBuilder.launch(cli(), spec), commandTimeout(), false, spec.sandboxNo());
        if (result.timedOut() || result.exitCode() != 0) {
            log.warn("沙箱容器启动失败: sandboxNo={}", spec.sandboxNo());
            throw operationFailed();
        }
        log.info("沙箱容器已启动: sandboxNo={}", spec.sandboxNo());
        return DockerCommandBuilder.containerName(spec.sandboxNo());
    }

    @Override
    public void stop(final String sandboxNo) {
        requireEnabled();
        DockerCommandBuilder.containerName(sandboxNo);
        final ProcessRunner.ProcessResult stop = run(DockerCommandBuilder.stop(cli(), sandboxNo),
                commandTimeout(), false, sandboxNo);
        if (!stop.timedOut() && stop.exitCode() == 0) {
            return;
        }
        final ProcessRunner.ProcessResult remove = run(DockerCommandBuilder.remove(cli(), sandboxNo),
                commandTimeout(), false, sandboxNo);
        if (remove.timedOut() || remove.exitCode() != 0) {
            log.warn("沙箱容器停止失败: sandboxNo={}", sandboxNo);
            throw operationFailed();
        }
    }

    @Override
    public boolean isRunning(final String sandboxNo) {
        requireEnabled();
        DockerCommandBuilder.containerName(sandboxNo);
        try {
            final ProcessRunner.ProcessResult result = run(
                    DockerCommandBuilder.inspect(cli(), sandboxNo), commandTimeout(), true, sandboxNo);
            return !result.timedOut() && result.exitCode() == 0
                    && "true".equals(result.stdout().trim());
        } catch (final RuntimeException e) {
            // 探活失败按“不在运行”处理，由启动对账纠偏（不冒充运行中）
            return false;
        }
    }

    @Override
    public JobExecResult exec(final String sandboxNo, final List<String> command,
            final Duration timeout) {
        requireEnabled();
        final int seconds = (int) Math.max(1L, timeout.toSeconds());
        final long started = System.nanoTime();
        final ProcessRunner.ProcessResult result = run(
                DockerCommandBuilder.exec(cli(), sandboxNo, command, seconds),
                timeout.plusSeconds(EXEC_GRACE_SECONDS), false, sandboxNo);
        final long durationMs = (System.nanoTime() - started) / 1_000_000L;
        if (result.timedOut()) {
            log.warn("沙箱作业执行超时: sandboxNo={}", sandboxNo);
            return new JobExecResult(JobExecResult.TIMEOUT_EXIT_CODE, true, durationMs);
        }
        return new JobExecResult(result.exitCode(), false, durationMs);
    }

    private ProcessRunner.ProcessResult run(final List<String> argv, final Duration timeout,
            final boolean capture, final String sandboxNo) {
        return processRunner.run(argv, timeout, capture);
    }

    private void requireEnabled() {
        if (!properties.getRuntime().isEnabled()) {
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE,
                    SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE);
        }
    }

    private void verifyPaths(final SandboxSpec spec) {
        requireInside(spec.inputDir(), properties.realWorkspaceRoot());
        requireInside(spec.outputDir(), properties.realWorkspaceRoot());
        requireInside(spec.jobsDir(), properties.realJobsRoot());
    }

    private void requireInside(final Path path, final Path root) {
        if (path == null || root == null) {
            throw operationFailed();
        }
        try {
            if (!path.toRealPath().startsWith(root)) {
                log.warn("沙箱路径越界（细节不入日志）");
                throw operationFailed();
            }
        } catch (final IOException e) {
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE, e);
        }
    }

    private String cli() {
        return properties.getRuntime().getDockerCli();
    }

    private Duration commandTimeout() {
        return properties.getRuntime().getCommandTimeout();
    }

    private static SandboxBizException operationFailed() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE);
    }
}
