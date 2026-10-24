package com.ctds.delivery.infrastructure;

import com.ctds.delivery.domain.SandboxSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * docker CLI argv 纯函数构造（hifi §4.2 / §4.3）：<b>零字符串拼接执行</b>，
 * 每个参数为独立 argv 元素（不经 shell），命令 / 镜像 / 作业全部来自配置白名单。
 *
 * <p>安全基线（缺一不可）：无网络、非 root、能力全丢、禁止提权、根只读 + tmpfs、
 * PID / 内存硬限、三段挂载（输入只读 / 输出可写 / 作业只读）、不挂 docker socket、
 * 不用 privileged / host 网络 / host PID。</p>
 */
public final class DockerCommandBuilder {

    private static final Pattern SANDBOX_NO = Pattern.compile("SB\\d{6}");
    private static final String CONTAINER_PREFIX = "ctds-sb-";
    private static final String USER = "1000:1000";
    private static final String NETWORK_NONE = "none";
    private static final String TMPFS = "/tmp:rw,noexec,nosuid,size=16m";
    private static final int STOP_GRACE_SECONDS = 5;

    private DockerCommandBuilder() {
    }

    /** 容器名由 sandboxNo 确定（可重建；容器 ID 不持久化）。 */
    public static String containerName(final String sandboxNo) {
        if (sandboxNo == null || !SANDBOX_NO.matcher(sandboxNo).matches()) {
            throw new IllegalArgumentException("sandboxNo 非法");
        }
        return CONTAINER_PREFIX + sandboxNo;
    }

    /** 启动容器 argv（常驻 sleep 保活，作业经 exec 进入）。 */
    public static List<String> launch(final String dockerCli, final SandboxSpec spec) {
        final String name = containerName(spec.sandboxNo());
        final String memory = spec.memoryMb() + "m";
        final List<String> argv = new ArrayList<>();
        argv.add(dockerCli);
        argv.add("run");
        argv.add("-d");
        argv.add("--name");
        argv.add(name);
        argv.add("--label");
        argv.add("com.ctds.sandbox=" + spec.sandboxNo());
        argv.add("--network");
        argv.add(NETWORK_NONE);
        argv.add("--cpus");
        argv.add(String.format(Locale.ROOT, "%.2f", spec.cpus()));
        argv.add("--memory");
        argv.add(memory);
        argv.add("--memory-swap");
        argv.add(memory);
        argv.add("--pids-limit");
        argv.add(String.valueOf(com.ctds.delivery.config.SandboxProperties.pidsLimit()));
        argv.add("--cap-drop");
        argv.add("ALL");
        argv.add("--security-opt");
        argv.add("no-new-privileges=true");
        argv.add("--read-only");
        argv.add("--tmpfs");
        argv.add(TMPFS);
        argv.add("--user");
        argv.add(USER);
        argv.add("-e");
        argv.add("PYTHONDONTWRITEBYTECODE=1");
        argv.add("-e");
        argv.add("PYTHONUNBUFFERED=1");
        argv.add("--mount");
        argv.add(bind(spec.inputDir(), "/work/input", true));
        argv.add("--mount");
        argv.add(bind(spec.outputDir(), "/work/output", false));
        argv.add("--mount");
        argv.add(bind(spec.jobsDir(), "/opt/ctds/jobs", true));
        argv.add("-w");
        argv.add("/work/output");
        argv.add("--rm");
        argv.add(spec.image());
        argv.add("sleep");
        argv.add("2147483647");
        return List.copyOf(argv);
    }

    /** 受控作业执行 argv：容器内 timeout 兜底杀进程，命令来自配置清单（不拼接用户输入）。 */
    public static List<String> exec(final String dockerCli, final String sandboxNo,
            final List<String> command, final int timeoutSeconds) {
        final String name = containerName(sandboxNo);
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("作业命令为空");
        }
        final List<String> argv = new ArrayList<>();
        argv.add(dockerCli);
        argv.add("exec");
        argv.add("--user");
        argv.add(USER);
        argv.add(name);
        argv.add("timeout");
        argv.add("-s");
        argv.add("KILL");
        argv.add(String.valueOf(timeoutSeconds));
        argv.addAll(command);
        return List.copyOf(argv);
    }

    /** 停止容器（--rm 自动移除；先礼后兵由调用方按需补 rm -f）。 */
    public static List<String> stop(final String dockerCli, final String sandboxNo) {
        return List.of(dockerCli, "stop", "-t", String.valueOf(STOP_GRACE_SECONDS),
                containerName(sandboxNo));
    }

    /** 强制移除（stop 失败兜底）。 */
    public static List<String> remove(final String dockerCli, final String sandboxNo) {
        return List.of(dockerCli, "rm", "-f", containerName(sandboxNo));
    }

    /** 探活（容器不存在时 docker inspect 非 0 退出，由调用方判 false）。 */
    public static List<String> inspect(final String dockerCli, final String sandboxNo) {
        return List.of(dockerCli, "inspect", "-f", "{{.State.Running}}", containerName(sandboxNo));
    }

    /** 单个 argv 元素的 bind 挂载（路径统一绝对化，规避 Windows 冒号 / 空格解析）。 */
    private static String bind(final java.nio.file.Path source, final String target,
            final boolean readOnly) {
        final String base = "type=bind,source=" + source.toAbsolutePath() + ",target=" + target;
        return readOnly ? base + ",readonly" : base;
    }
}
