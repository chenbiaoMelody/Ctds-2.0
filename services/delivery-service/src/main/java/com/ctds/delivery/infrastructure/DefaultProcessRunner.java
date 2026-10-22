package com.ctds.delivery.infrastructure;

import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessRunner} 默认实现（hifi §4.1）：<b>全仓唯一 ProcessBuilder 落点</b>。
 *
 * <p>安全口径：argv 数组（无 shell）→ 无命令注入面；作业级输出一律 {@code Redirect.DISCARD}
 * （内容可能含数据，不读、不落盘、不落日志）；仅探活场景捕获至多 {@link #MAX_CAPTURE_BYTES}
 * 字节（docker inspect 输出极小，不存在管道背压）。超时一律强制销毁进程。</p>
 */
@Component
public class DefaultProcessRunner implements ProcessRunner {

    /** 捕获上限（仅探活使用；防止异常输出占用内存）。 */
    private static final int MAX_CAPTURE_BYTES = 4096;
    private static final long DESTROY_WAIT_SECONDS = 5L;

    @Override
    public ProcessResult run(final List<String> argv, final Duration timeout,
            final boolean captureStdout) {
        final ProcessBuilder builder = new ProcessBuilder(argv);
        if (captureStdout) {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        } else {
            // 作业 stdout/stderr 可能含数据：直接丢弃，既不落盘也不进内存
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process process = null;
        try {
            process = builder.start();
            final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroy(process);
                return new ProcessResult(-1, true, "");
            }
            final String output = captureStdout ? readBounded(process.getInputStream()) : "";
            return new ProcessResult(process.exitValue(), false, output);
        } catch (final IOException e) {
            destroy(process);
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_OPERATION_FAILED,
                    SandboxErrorCodes.SANDBOX_OPERATION_FAILED_MESSAGE, e);
        }
    }

    private static String readBounded(final InputStream input) throws IOException {
        try (input) {
            final byte[] data = input.readNBytes(MAX_CAPTURE_BYTES);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void destroy(final Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
