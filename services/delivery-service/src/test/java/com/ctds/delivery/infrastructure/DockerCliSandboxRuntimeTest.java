package com.ctds.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.JobExecResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 运行时适配器单测（hifi §4.3）：Java 侧兜底超时 = 作业超时 + 10s（容器内 timeout 负责杀进程），
 * 超时结论统一为 {@link JobExecResult#TIMEOUT_EXIT_CODE}；镜像 / 命令不落日志。
 */
class DockerCliSandboxRuntimeTest {

    @Test
    void execAddsTenSecondGraceToProcessTimeout() {
        final List<Duration> processTimeouts = new ArrayList<>();
        final ProcessRunner runner = (argv, timeout, capture) -> {
            processTimeouts.add(timeout);
            return new ProcessRunner.ProcessResult(0, false, "");
        };
        final DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(properties(), runner);

        runtime.exec("SB000001", List.of("python3", "/opt/ctds/jobs/dataset_profile.py"),
                Duration.ofSeconds(60));

        assertThat(processTimeouts).containsExactly(Duration.ofSeconds(70));
    }

    @Test
    void execTimeoutMapsToTimeoutResult() {
        final ProcessRunner runner = (argv, timeout, capture) ->
                new ProcessRunner.ProcessResult(137, true, "");
        final DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(properties(), runner);

        final JobExecResult result = runtime.exec("SB000001",
                List.of("python3", "/opt/ctds/jobs/input_inventory.py"), Duration.ofSeconds(30));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(JobExecResult.TIMEOUT_EXIT_CODE);
    }

    private static SandboxProperties properties() {
        final SandboxProperties properties = new SandboxProperties();
        properties.getRuntime().setEnabled(true);
        return properties;
    }
}
