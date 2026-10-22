package com.ctds.delivery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 沙箱配置启动校验单测（hifi §1.2 / §9 配置组 3 例）：配额越界、镜像非显式 tag、
 * 作业清单非法均 fail-fast；运行时关闭时允许空根目录（fail-closed 503）。
 */
class SandboxPropertiesTest {

    @TempDir
    Path temp;

    @Test
    void validConfigurationPassesValidation() {
        final SandboxProperties properties = validProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getJobs()).hasSize(1);
        assertThat(properties.getRuntime().isEnabled()).isTrue();
    }

    @Test
    void quotaOutOfRangeFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getQuota().setMemoryMb(64);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void latestImageFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getRuntime().setImage("python:latest");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void untaggedImageFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getRuntime().setImage("python");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jobCommandNotWhitelistedFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getJobs().get(0).setCommand(List.of("sh", "-c", "echo hi"));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidJobIdFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getJobs().get(0).setId("Bad Id");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateJobIdFailsStartup() {
        final SandboxProperties properties = validProperties();
        final SandboxProperties.Job second = new SandboxProperties.Job();
        second.setId(properties.getJobs().get(0).getId());
        second.setCommand(List.of("python3", "/opt/ctds/jobs/input_inventory.py"));
        properties.getJobs().add(second);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jobTimeoutAboveTtlFailsStartup() {
        final SandboxProperties properties = validProperties();
        properties.getQuota().setTtl(Duration.ofMinutes(1));
        properties.getJobs().get(0).setTimeout(Duration.ofMinutes(5));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeEnabledRequiresWritableRoots() {
        final SandboxProperties properties = validProperties();
        properties.setWorkspaceRoot(null);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeDisabledAllowsEmptyRoots() {
        final SandboxProperties properties = new SandboxProperties();
        properties.getRuntime().setEnabled(false);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    private SandboxProperties validProperties() {
        final SandboxProperties properties = new SandboxProperties();
        properties.setWorkspaceRoot(temp.resolve("workspace").toString());
        properties.setJobsRoot(temp.resolve("jobs").toString());
        properties.getRuntime().setEnabled(true);
        final SandboxProperties.Job job = new SandboxProperties.Job();
        job.setId("dataset-profile");
        job.setDisplayName("数据集画像");
        job.setCommand(List.of("python3", "/opt/ctds/jobs/dataset_profile.py", "/work/input"));
        job.setTimeout(Duration.ofSeconds(120));
        properties.setJobs(new java.util.ArrayList<>(List.of(job)));
        return properties;
    }
}
