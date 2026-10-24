package com.ctds.delivery.infrastructure;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.FileSource;
import com.ctds.delivery.domain.SandboxBizException;
import com.ctds.delivery.domain.SandboxErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 沙箱工作区（hifi §5）：输入按文件源复制（目标名 = fileId，不用展示名做路径）、
 * 输出目录可写、结果只出元数据（名称 / 字节数 / SM3）。
 *
 * <p><b>数据面口径</b>：不提供任何内容读取方法；文件内容只存在于工作区与容器内。
 * 所有目标路径 normalize 后必须落在任务根内，符号链接一律拒绝。</p>
 */
@Component
public class SandboxWorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(SandboxWorkspaceStore.class);
    private static final Pattern SANDBOX_NO = Pattern.compile("SB\\d{6}");
    private static final Pattern RESULT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String MANIFEST = "manifest.tsv";
    /** 超过该字节数的结果文件不计算摘要（流式摘要登记演进，hifi §5.6）。 */
    private static final long MAX_CHECKSUM_BYTES = 64L * 1024 * 1024;

    private final SandboxProperties properties;
    private final Sm3Service sm3Service;

    public SandboxWorkspaceStore(final SandboxProperties properties, final Sm3Service sm3Service) {
        this.properties = properties;
        this.sm3Service = sm3Service;
    }

    /**
     * 准备任务工作区并复制输入；任何失败（上限 / IO / containment）→ 清理残留后 fail-closed。
     *
     * @return 任务根（input / output 已建）
     */
    public Path prepare(final String sandboxNo, final List<FileSource> files) {
        final Path taskRoot = taskRoot(sandboxNo);
        final List<FileSource> sources = files == null ? List.of() : files;
        if (sources.size() > properties.getQuota().getMaxInputFiles()) {
            throw unavailable();
        }
        long total = 0L;
        for (final FileSource source : sources) {
            total += source.size();
        }
        if (total > properties.getQuota().getMaxInputBytes()) {
            throw unavailable();
        }
        try {
            final Path input = Files.createDirectories(taskRoot.resolve(INPUT));
            final Path output = Files.createDirectories(taskRoot.resolve(OUTPUT));
            makeWorldWritable(output);
            final StringBuilder manifest = new StringBuilder();
            for (final FileSource source : sources) {
                if (Files.isSymbolicLink(source.path())
                        || !Files.isRegularFile(source.path(), LinkOption.NOFOLLOW_LINKS)) {
                    throw unavailable();
                }
                final Path target = contained(input, source.fileId());
                Files.copy(source.path(), target, StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
                manifest.append(source.fileId()).append('\t')
                        .append(source.displayName()).append('\t')
                        .append(source.size()).append('\n');
            }
            Files.writeString(input.resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);
            return taskRoot;
        } catch (final IOException | RuntimeException e) {
            deleteQuietly(sandboxNo);
            if (e instanceof SandboxBizException biz) {
                throw biz;
            }
            throw new SandboxBizException(SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE,
                    SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE, e);
        }
    }

    /** 结果元数据（仅输出目录顶层普通文件；无内容、无路径）。 */
    public List<ResultFile> listResults(final String sandboxNo) {
        final Path output = taskRoot(sandboxNo).resolve(OUTPUT);
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        final List<ResultFile> results = new ArrayList<>();
        try (Stream<Path> paths = Files.list(output)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> RESULT_NAME.matcher(path.getFileName().toString()).matches())
                    .forEach(path -> results.add(toResultFile(path)));
        } catch (final IOException e) {
            log.warn("沙箱结果目录不可读: sandboxNo={}", sandboxNo);
            return List.of();
        }
        results.sort(Comparator.comparing(ResultFile::fileName));
        return List.copyOf(results);
    }

    /** 清理任务工作区（幂等；不跟随符号链接；失败不影响业务结论）。 */
    public void deleteQuietly(final String sandboxNo) {
        final Path taskRoot = taskRoot(sandboxNo);
        if (!Files.exists(taskRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(taskRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // 清理失败不改变业务结论（工作区残留由运维收口）
                }
            });
        } catch (final IOException e) {
            log.warn("沙箱工作区清理失败: sandboxNo={}", sandboxNo);
        }
    }

    private Path taskRoot(final String sandboxNo) {
        if (sandboxNo == null || !SANDBOX_NO.matcher(sandboxNo).matches()) {
            throw new IllegalArgumentException("sandboxNo 非法");
        }
        final Path root = workspaceRoot();
        final Path taskRoot = root.resolve(sandboxNo).normalize();
        if (!taskRoot.startsWith(root)) {
            throw new IllegalArgumentException("sandboxNo 越界");
        }
        return taskRoot;
    }

    private Path workspaceRoot() {
        final Path real = properties.realWorkspaceRoot();
        if (real != null) {
            return real;
        }
        final String text = properties.getWorkspaceRoot();
        if (text == null || text.isBlank()) {
            throw unavailable();
        }
        return Paths.get(text).toAbsolutePath().normalize();
    }

    private static Path contained(final Path base, final String fileId) {
        if (fileId == null || fileId.isBlank() || MANIFEST.equals(fileId)) {
            throw unavailable();
        }
        final Path target = base.resolve(fileId).normalize();
        if (!target.startsWith(base) || !target.getParent().equals(base)) {
            throw unavailable();
        }
        return target;
    }

    private ResultFile toResultFile(final Path path) {
        try {
            final long size = Files.size(path);
            final String checksum = size <= MAX_CHECKSUM_BYTES ? checksum(path) : null;
            final LocalDateTime createdAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
            return new ResultFile(path.getFileName().toString(), size, checksum, createdAt);
        } catch (final IOException e) {
            return new ResultFile(path.getFileName().toString(), 0L, null, null);
        }
    }

    private String checksum(final Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] data = input.readNBytes((int) MAX_CHECKSUM_BYTES + 1);
            if (data.length > MAX_CHECKSUM_BYTES) {
                return null;
            }
            return sm3Service.digestHex(data);
        }
    }

    private static void makeWorldWritable(final Path output) {
        try {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (final UnsupportedOperationException | IOException e) {
            // 非 POSIX 文件系统（演示 Windows Docker Desktop）跳过；Linux 生产由部署侧 chown
        }
    }

    private static SandboxBizException unavailable() {
        return new SandboxBizException(SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE,
                SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE);
    }

    /** 结果物元数据（不含内容与路径）。 */
    public record ResultFile(String fileName, long sizeBytes, String checksum, LocalDateTime createdAt) {
    }
}
