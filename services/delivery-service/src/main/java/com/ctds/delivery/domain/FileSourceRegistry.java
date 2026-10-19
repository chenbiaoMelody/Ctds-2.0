package com.ctds.delivery.domain;

import com.ctds.delivery.config.DeliveryProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件源清单校验与解析（Q3-A，hifi §5.1）：配置化清单 + 源根；启动 fail-fast。
 * <b>安全边界</b>：fileId 仅作精确键，用户不能提供路径；实际路径来自清单且必须落在源根内、
 * 非符号链接、普通文件、0 &lt; size &le; maxBytes。任何清单非法 = 启动失败（静默跳过不可信）。
 */
@Component
public class FileSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileSourceRegistry.class);
    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** common-crypto 的明文硬上限（ADR-006 / Inputs.MAX_INPUT_BYTES）。 */
    private static final long CRYPTO_MAX_BYTES = 64L * 1024 * 1024;

    private final DeliveryProperties properties;
    private final Map<String, FileSource> sources = new LinkedHashMap<>();
    private boolean enabled;
    private Path realRoot;

    public FileSourceRegistry(final DeliveryProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void load() {
        if (properties.getFile().getMaxBytes() <= 0
                || properties.getFile().getMaxBytes() > CRYPTO_MAX_BYTES) {
            throw new IllegalStateException("ctds.delivery.file.max-bytes 必须在 1~64MiB 之间");
        }
        if (properties.getFiles().isEmpty()) {
            enabled = false;
            log.info("未配置文件源清单，文件交付不可用（API 交付不受影响）");
            return;
        }
        final String rootText = properties.getSourceRoot();
        if (rootText == null || rootText.isBlank()) {
            throw new IllegalStateException("配置了 ctds.delivery.files 但 source-root 为空");
        }
        final Path root = Paths.get(rootText).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("文件源根目录不存在或不是目录");
        }
        try {
            realRoot = root.toRealPath();
        } catch (final IOException e) {
            throw new IllegalStateException("文件源根目录不可解析", e);
        }
        for (final DeliveryProperties.FileEntry entry : properties.getFiles()) {
            final FileSource source = validate(entry, realRoot);
            if (sources.putIfAbsent(source.fileId(), source) != null) {
                throw new IllegalStateException("重复的 fileId: " + source.fileId());
            }
        }
        enabled = true;
        log.info("文件源清单加载完成: files={}", sources.size());
    }

    /** 文件交付是否可用（清单已配置且通过启动校验）。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 请求时解析文件源：未启用/文件丢失/不可读 → 依赖不可用；未登记/不属于产品 → 范围不符；
     * 超上限 → 文件过大。返回的路径只供服务内部读取。
     */
    public FileSource require(final String fileId, final long contractProductId) {
        if (!enabled) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                    DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
        }
        final FileSource source = sources.get(fileId);
        if (source == null || source.productId() != contractProductId) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
                    DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH_MESSAGE);
        }
        final Path path = source.path();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                    DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
        }
        try {
            // 请求时复核真实路径：启动后若父目录/末级被替换为链接，fail-closed
            final Path real = path.toRealPath();
            if (!real.equals(path) || !real.startsWith(realRoot)) {
                throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                        DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
            }
            final long size = Files.size(real);
            if (size <= 0) {
                throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                        DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
            }
            if (size > properties.getFile().getMaxBytes()) {
                throw new DeliveryBizException(DeliveryErrorCodes.FILE_TOO_LARGE,
                        DeliveryErrorCodes.FILE_TOO_LARGE_MESSAGE);
            }
            return new FileSource(source.fileId(), source.productId(), path, source.displayName(), size);
        } catch (final IOException e) {
            log.warn("文件源不可读: fileId={}", fileId);
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                    DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
        }
    }

    /** 测试/诊断只读视图（不暴露路径）。 */
    public Optional<FileSource> find(final String fileId) {
        return Optional.ofNullable(sources.get(fileId));
    }

    private FileSource validate(final DeliveryProperties.FileEntry entry, final Path realRoot) {
        final String fileId = entry.getFileId();
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            throw new IllegalStateException("fileId 非法（仅 [A-Za-z0-9._-]{1,64}）");
        }
        if (entry.getProductId() <= 0) {
            throw new IllegalStateException("fileId=" + fileId + " 的 productId 必须为正数");
        }
        final String displayName = entry.getDisplayName();
        if (displayName == null || displayName.isBlank() || displayName.length() > 255
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("fileId=" + fileId + " 的 display-name 非法");
        }
        final String relative = entry.getPath();
        if (relative == null || relative.isBlank() || Paths.get(relative).isAbsolute()) {
            throw new IllegalStateException("fileId=" + fileId + " 的 path 必须为相对路径");
        }
        final Path candidate = realRoot.resolve(relative).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new IllegalStateException("fileId=" + fileId + " 的 path 越出源根");
        }
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("fileId=" + fileId + " 的文件不存在或不是普通文件");
        }
        try {
            final Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new IllegalStateException("fileId=" + fileId + " 的真实路径越出源根");
            }
            final long size = Files.size(real);
            if (size <= 0 || size > properties.getFile().getMaxBytes()) {
                throw new IllegalStateException("fileId=" + fileId + " 的文件大小超出 1~max-bytes");
            }
            return new FileSource(fileId, entry.getProductId(), real, displayName, size);
        } catch (final IOException e) {
            throw new IllegalStateException("fileId=" + fileId + " 的文件不可解析", e);
        }
    }
}
