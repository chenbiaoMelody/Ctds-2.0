package com.ctds.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.delivery.domain.SandboxSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 容器命令 argv 单测（hifi §4.2 / §4.3 / §9 运行时组 6 例）：安全基线逐项、
 * 无 privileged / socket / host 网络、容器名与 sandboxNo 正则、exec 带超时且无 shell。
 */
class DockerCommandBuilderTest {

    private static final String DOCKER = "docker";

    @Test
    void launchAppliesFullSecurityBaseline() {
        final List<String> argv = DockerCommandBuilder.launch(DOCKER, spec());
        assertThat(join(argv)).contains("--network none", "--cap-drop ALL",
                "--security-opt no-new-privileges=true", "--read-only", "--pids-limit 128",
                "--user 1000:1000", "--memory 512m", "--memory-swap 512m");
        assertThat(join(argv)).contains("noexec", "nosuid");
        assertThat(argv).containsSubsequence("--tmpfs");
        assertThat(argv.subList(argv.size() - 3, argv.size()))
                .containsExactly("python:3.12-alpine", "sleep", "2147483647");
    }

    @Test
    void launchNeverUsesPrivilegedSocketOrHostNetwork() {
        final String joined = join(DockerCommandBuilder.launch(DOCKER, spec()));
        assertThat(joined).doesNotContain("--privileged", "docker.sock", "--network host",
                "--pid host", "--userns host");
        assertThat(joined).contains("--network none");
    }

    @Test
    void launchMountsExactlyThreeScopes() {
        final List<String> argv = DockerCommandBuilder.launch(DOCKER, spec());
        final long mounts = argv.stream().filter("--mount"::equals).count();
        assertThat(mounts).isEqualTo(3);
        assertThat(join(argv)).contains("target=/work/input,readonly", "target=/work/output",
                "target=/opt/ctds/jobs,readonly");
        assertThat(join(argv)).contains("source=" + spec().inputDir().toAbsolutePath());
    }

    @Test
    void launchUsesDeterministicContainerName() {
        final List<String> argv = DockerCommandBuilder.launch(DOCKER, spec());
        assertThat(argv).containsSubsequence("--name", "ctds-sb-SB000001");
        assertThat(DockerCommandBuilder.containerName("SB000001")).isEqualTo("ctds-sb-SB000001");
    }

    @Test
    void invalidSandboxNoRejected() {
        assertThatThrownBy(() -> DockerCommandBuilder.containerName("SB1")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerCommandBuilder.launch(DOCKER,
                new SandboxSpec("../x", spec().inputDir(), spec().outputDir(), spec().jobsDir(),
                        "python:3.12-alpine", 1.0, 512))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execWrapsTimeoutWithoutShell() {
        final List<String> argv = DockerCommandBuilder.exec(DOCKER, "SB000001",
                List.of("python3", "/opt/ctds/jobs/dataset_profile.py", "/work/input"), 120);
        assertThat(argv).containsSubsequence("exec", "--user", "1000:1000", "ctds-sb-SB000001",
                "timeout", "-s", "KILL", "120", "python3");
        assertThat(argv).doesNotContain("sh", "-c", "bash");
        assertThat(argv.get(argv.size() - 1)).isEqualTo("/work/input");
    }

    @Test
    void stopAndInspectArgvAreBounded() {
        assertThat(DockerCommandBuilder.stop(DOCKER, "SB000001"))
                .containsExactly("docker", "stop", "-t", "5", "ctds-sb-SB000001");
        assertThat(DockerCommandBuilder.inspect(DOCKER, "SB000001"))
                .containsExactly("docker", "inspect", "-f", "{{.State.Running}}", "ctds-sb-SB000001");
    }

    private static SandboxSpec spec() {
        final Path root = Path.of("build-output", "sandbox-test");
        return new SandboxSpec("SB000001", root.resolve("input"), root.resolve("output"),
                root.resolve("jobs"), "python:3.12-alpine", 1.0, 512);
    }

    private static String join(final List<String> argv) {
        return String.join(" ", argv);
    }
}
