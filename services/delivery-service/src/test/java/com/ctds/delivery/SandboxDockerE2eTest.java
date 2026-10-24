package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.FileSource;
import com.ctds.delivery.domain.JobExecResult;
import com.ctds.delivery.domain.SandboxSpec;
import com.ctds.delivery.infrastructure.DefaultProcessRunner;
import com.ctds.delivery.infrastructure.DockerCliSandboxRuntime;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真 Docker 走查用例（hifi §9.1）：<b>默认跳过</b>，走查段以 {@code CTDS_SANDBOX_E2E=1} 启用并留痕。
 * 覆盖：起容器并核安全基线 / 输入只读 / 受控作业执行 / 网络不可达。
 *
 * <p>前置：Docker Desktop（OSType=linux）+ `python:3.12-alpine`；不启动 Spring 上下文。</p>
 */
@EnabledIfEnvironmentVariable(named = "CTDS_SANDBOX_E2E", matches = "1")
class SandboxDockerE2eTest {

    private static final String SANDBOX_NO = "SB000001";
    private static final DefaultProcessRunner RUNNER = new DefaultProcessRunner();

    @TempDir
    Path temp;

    private SandboxProperties properties;
    private DockerCliSandboxRuntime runtime;
    private SandboxWorkspaceStore store;

    @BeforeEach
    void setUp() throws Exception {
        properties = new SandboxProperties();
        properties.setWorkspaceRoot(Files.createDirectories(temp.resolve("workspace")).toString());
        properties.setJobsRoot(Files.createDirectories(temp.resolve("jobs")).toString());
        properties.getRuntime().setEnabled(true);
        properties.getRuntime().setCommandTimeout(Duration.ofSeconds(60));
        properties.validate();
        runtime = new DockerCliSandboxRuntime(properties, RUNNER);
        store = new SandboxWorkspaceStore(properties, new Sm3Service());
        Files.writeString(properties.realJobsRoot().resolve("probe.py"),
                "import sys\nwith open(sys.argv[2], 'w') as f:\n    f.write('probe-ok')\n",
                StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            try {
                runtime.stop(SANDBOX_NO);
            } catch (final RuntimeException ignored) {
                // 清理 best-effort
            }
        }
        if (store != null) {
            store.deleteQuietly(SANDBOX_NO);
        }
    }

    @Test
    void containerStartsWithSecurityBaseline() throws Exception {
        launch();
        assertThat(runtime.isRunning(SANDBOX_NO)).isTrue();
        final DefaultProcessRunner.ProcessResult inspect = RUNNER.run(List.of("docker", "inspect",
                "-f", "{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|"
                        + "{{.HostConfig.Privileged}}|{{.Config.User}}",
                "ctds-sb-" + SANDBOX_NO), Duration.ofSeconds(30), true);
        assertThat(inspect.exitCode()).isZero();
        assertThat(inspect.stdout()).contains("none", "true", "false", "1000:1000");
    }

    @Test
    void inputMountIsReadOnly() throws Exception {
        launch();
        final JobExecResult write = runtime.exec(SANDBOX_NO,
                List.of("python3", "-c", "open('/work/input/sample','w').write('x')"),
                Duration.ofSeconds(30));
        assertThat(write.exitCode()).isNotZero();
    }

    @Test
    void controlledJobProducesResult() throws Exception {
        launch();
        final JobExecResult result = runtime.exec(SANDBOX_NO,
                List.of("python3", "/opt/ctds/jobs/probe.py", "/work/input",
                        "/work/output/probe.md"),
                Duration.ofSeconds(60));
        assertThat(result.exitCode()).isZero();
        final List<SandboxWorkspaceStore.ResultFile> results = store.listResults(SANDBOX_NO);
        assertThat(results).extracting(SandboxWorkspaceStore.ResultFile::fileName)
                .contains("probe.md");
        assertThat(results.get(0).checksum()).isEqualTo(new Sm3Service().digestHex("probe-ok"));
    }

    @Test
    void containerHasNoNetwork() throws Exception {
        launch();
        final JobExecResult result = runtime.exec(SANDBOX_NO,
                List.of("python3", "-c",
                        "import socket;socket.create_connection(('1.1.1.1',80),timeout=3)"),
                Duration.ofSeconds(30));
        assertThat(result.exitCode()).isNotZero();
    }

    private void launch() throws Exception {
        final Path data = Files.createDirectories(temp.resolve("source"));
        Files.writeString(data.resolve("sample"), "a,b\n1,2\n", StandardCharsets.UTF_8);
        final Path taskRoot = store.prepare(SANDBOX_NO,
                List.of(new FileSource("sample", 13, data.resolve("sample"), "样品.csv", 8)));
        runtime.launch(new SandboxSpec(SANDBOX_NO, taskRoot.resolve("input"),
                taskRoot.resolve("output"), properties.realJobsRoot(),
                properties.getRuntime().getImage(), 1.0, 512));
    }
}
