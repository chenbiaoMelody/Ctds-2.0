package com.ctds.delivery.config;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置（hifi §1.2）。配额 / 运行时 / 受控作业清单全部配置化，申请方不可自选；
 * 启动 fail-fast：非法值一律启动失败（静默降级不可信）。
 *
 * <p><b>安全边界</b>：作业命令只来自本配置（请求只给 jobId），命令首元素白名单 python3；
 * 镜像必须显式 tag（ADR-013）；运行时默认关闭（k8s 不注入 docker socket）。</p>
 */
@ConfigurationProperties(prefix = "ctds.sandbox")
public class SandboxProperties {

    private static final Pattern JOB_ID = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern IMAGE_TAG = Pattern.compile("^[a-z0-9._/-]+:[A-Za-z0-9._-]+$");
    private static final double MIN_CPUS = 0.1;
    private static final double MAX_CPUS = 4.0;
    private static final int MIN_MEMORY_MB = 128;
    private static final int MAX_MEMORY_MB = 4096;
    private static final long MIN_INPUT_BYTES = 1024L * 1024;
    private static final long MAX_INPUT_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_COMMAND_ELEMENTS = 32;
    private static final int MAX_COMMAND_ELEMENT_CHARS = 512;
    private static final int MAX_PIDS = 128;

    /** 平台工作区根（输入副本 / 输出目录）；未配置 = 沙箱不可用。 */
    private String workspaceRoot;

    /** 受控作业脚本根（只读挂载到 /opt/ctds/jobs）；未配置 = 沙箱不可用。 */
    private String jobsRoot;

    private final Quota quota = new Quota();

    private final Runtime runtime = new Runtime();

    private final Sweep sweep = new Sweep();

    private List<Job> jobs = new ArrayList<>();

    private Path realWorkspaceRoot;
    private Path realJobsRoot;

    @PostConstruct
    public void validate() {
        quota.validate();
        runtime.validate();
        if (runtime.isEnabled()) {
            realWorkspaceRoot = requireWritableDirectory(workspaceRoot, "ctds.sandbox.workspace-root");
            realJobsRoot = requireWritableDirectory(jobsRoot, "ctds.sandbox.jobs-root");
        }
        validateJobs();
    }

    private void validateJobs() {
        final Set<String> ids = new HashSet<>();
        for (final Job job : jobs) {
            if (job.getId() == null || !JOB_ID.matcher(job.getId()).matches()) {
                throw new IllegalStateException("作业 id 非法（仅 [a-z0-9-]{1,64}）");
            }
            if (!ids.add(job.getId())) {
                throw new IllegalStateException("重复的作业 id: " + job.getId());
            }
            final List<String> command = job.getCommand();
            if (command == null || command.isEmpty() || command.size() > MAX_COMMAND_ELEMENTS) {
                throw new IllegalStateException("作业 " + job.getId() + " 的命令长度非法");
            }
            for (final String element : command) {
                if (element == null || element.isBlank() || element.length() > MAX_COMMAND_ELEMENT_CHARS
                        || element.indexOf('\u0000') >= 0 || element.indexOf('\n') >= 0
                        || element.indexOf('\r') >= 0) {
                    throw new IllegalStateException("作业 " + job.getId() + " 的命令元素非法");
                }
            }
            if (!"python3".equals(command.get(0))) {
                throw new IllegalStateException("作业 " + job.getId() + " 的命令首元素仅允许 python3");
            }
            final Duration timeout = job.getTimeout();
            if (timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofSeconds(600)) > 0
                    || timeout.compareTo(quota.getTtl()) > 0) {
                throw new IllegalStateException("作业 " + job.getId() + " 的超时须为 1s~600s 且不超过任务时长");
            }
        }
    }

    private static Path requireWritableDirectory(final String text, final String key) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(key + " 未配置（运行时启用时必填）");
        }
        final Path path = Paths.get(text).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
            final Path real = path.toRealPath();
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(real)) {
                throw new IllegalStateException(key + " 不是可写目录");
            }
            return real;
        } catch (final IOException e) {
            throw new IllegalStateException(key + " 不可用", e);
        }
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(final String value) {
        this.workspaceRoot = value;
    }

    public String getJobsRoot() {
        return jobsRoot;
    }

    public void setJobsRoot(final String value) {
        this.jobsRoot = value;
    }

    public Quota getQuota() {
        return quota;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Sweep getSweep() {
        return sweep;
    }

    public List<Job> getJobs() {
        return jobs;
    }

    public void setJobs(final List<Job> value) {
        this.jobs = (value == null) ? new ArrayList<>() : value;
    }

    /** 已解析的工作区真实根（内部使用；不对外暴露）。 */
    public Path realWorkspaceRoot() {
        return realWorkspaceRoot;
    }

    /** 已解析的作业脚本真实根（内部使用；不对外暴露）。 */
    public Path realJobsRoot() {
        return realJobsRoot;
    }

    /** 按 jobId 查找受控作业。 */
    public Optional<Job> findJob(final String jobId) {
        return jobs.stream().filter(job -> job.getId().equals(jobId)).findFirst();
    }

    /** 配额（平台统一默认值，申请不可自选；hifi §1.2 / Q6-A）。 */
    public static class Quota {

        private double cpus = 1.0;
        private int memoryMb = 512;
        private Duration ttl = Duration.ofHours(2);
        private Duration jobTimeout = Duration.ofSeconds(120);
        private int maxActivePerRequester = 2;
        private int maxActiveGlobal = 8;
        private int maxInputFiles = 32;
        private long maxInputBytes = 268435456L;

        void validate() {
            if (cpus < MIN_CPUS || cpus > MAX_CPUS) {
                throw new IllegalStateException("ctds.sandbox.quota.cpus 须在 0.1~4");
            }
            if (memoryMb < MIN_MEMORY_MB || memoryMb > MAX_MEMORY_MB) {
                throw new IllegalStateException("ctds.sandbox.quota.memory-mb 须在 128~4096");
            }
            if (ttl == null || ttl.compareTo(Duration.ofMinutes(1)) < 0
                    || ttl.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalStateException("ctds.sandbox.quota.ttl 须在 1m~24h");
            }
            if (jobTimeout == null || jobTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || jobTimeout.compareTo(Duration.ofSeconds(600)) > 0) {
                throw new IllegalStateException("ctds.sandbox.quota.job-timeout 须在 1s~600s");
            }
            if (maxActivePerRequester < 1 || maxActivePerRequester > 100
                    || maxActiveGlobal < maxActivePerRequester || maxActiveGlobal > 100) {
                throw new IllegalStateException("ctds.sandbox.quota 并发上限非法（1~100，且全局不小于单需求方）");
            }
            if (maxInputFiles < 1 || maxInputFiles > 256) {
                throw new IllegalStateException("ctds.sandbox.quota.max-input-files 须在 1~256");
            }
            if (maxInputBytes < MIN_INPUT_BYTES || maxInputBytes > MAX_INPUT_BYTES) {
                throw new IllegalStateException("ctds.sandbox.quota.max-input-bytes 须在 1MiB~1GiB");
            }
        }

        public double getCpus() {
            return cpus;
        }

        public void setCpus(final double value) {
            this.cpus = value;
        }

        public int getMemoryMb() {
            return memoryMb;
        }

        public void setMemoryMb(final int value) {
            this.memoryMb = value;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(final Duration value) {
            this.ttl = value;
        }

        public Duration getJobTimeout() {
            return jobTimeout;
        }

        public void setJobTimeout(final Duration value) {
            this.jobTimeout = value;
        }

        public int getMaxActivePerRequester() {
            return maxActivePerRequester;
        }

        public void setMaxActivePerRequester(final int value) {
            this.maxActivePerRequester = value;
        }

        public int getMaxActiveGlobal() {
            return maxActiveGlobal;
        }

        public void setMaxActiveGlobal(final int value) {
            this.maxActiveGlobal = value;
        }

        public int getMaxInputFiles() {
            return maxInputFiles;
        }

        public void setMaxInputFiles(final int value) {
            this.maxInputFiles = value;
        }

        public long getMaxInputBytes() {
            return maxInputBytes;
        }

        public void setMaxInputBytes(final long value) {
            this.maxInputBytes = value;
        }
    }

    /** 容器运行时（默认关闭 = fail-closed；ADR-013 显式 tag）。 */
    public static class Runtime {

        private boolean enabled;
        private String dockerCli = "docker";
        private String image = "python:3.12-alpine";
        private Duration commandTimeout = Duration.ofSeconds(30);

        void validate() {
            if (image == null || !IMAGE_TAG.matcher(image).matches()
                    || image.endsWith(":latest") || image.contains(":latest@")) {
                throw new IllegalStateException("ctds.sandbox.runtime.image 必须为显式 tag 且不得为 latest");
            }
            if (commandTimeout == null || commandTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || commandTimeout.compareTo(Duration.ofSeconds(300)) > 0) {
                throw new IllegalStateException("ctds.sandbox.runtime.command-timeout 须在 1s~300s");
            }
            if (dockerCli == null || dockerCli.isBlank() || dockerCli.length() > 128) {
                throw new IllegalStateException("ctds.sandbox.runtime.docker-cli 非法");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }

        public String getDockerCli() {
            return dockerCli;
        }

        public void setDockerCli(final String value) {
            this.dockerCli = value;
        }

        public String getImage() {
            return image;
        }

        public void setImage(final String value) {
            this.image = value;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(final Duration value) {
            this.commandTimeout = value;
        }
    }

    /** 到期扫描（单实例语义；多实例需选主，登记演进）。 */
    public static class Sweep {

        private boolean enabled = true;
        private long fixedDelayMs = 60000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(final long value) {
            this.fixedDelayMs = value;
        }
    }

    /** 受控作业（jobId → 固定 argv + 超时；不接受用户命令 / 路径）。 */
    public static class Job {

        private String id;
        private String displayName;
        private List<String> command = new ArrayList<>();
        private Duration timeout = Duration.ofSeconds(120);

        public String getId() {
            return id;
        }

        public void setId(final String value) {
            this.id = value;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(final String value) {
            this.displayName = value;
        }

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(final List<String> value) {
            this.command = (value == null) ? new ArrayList<>() : value;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration value) {
            this.timeout = value;
        }
    }

    /** 容器 PID 上限（固定安全基线，不配置化）。 */
    public static int pidsLimit() {
        return MAX_PIDS;
    }
}
