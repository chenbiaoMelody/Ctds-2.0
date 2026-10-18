package com.ctds.delivery.infrastructure;

import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 加密交付包存储（hifi §5.2）：包只写加密信封；明文不落盘。包标识由服务生成，
 * 路径由存储根 + 标识解析（不接受用户路径）；原子落盘，失败不留半写包。
 */
@Component
public class FilePackageStore {

    private static final Logger log = LoggerFactory.getLogger(FilePackageStore.class);
    private static final Pattern PACKAGE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final DeliveryProperties properties;
    private Path root;
    private boolean available;

    public FilePackageStore(final DeliveryProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        final String text = properties.getStorageRoot();
        if (text == null || text.isBlank()) {
            available = false;
            log.info("未配置存储根，文件交付不可用（API 交付不受影响）");
            return;
        }
        root = Paths.get(text).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (final IOException e) {
            throw new IllegalStateException("交付包存储根不可创建", e);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root)) {
            throw new IllegalStateException("交付包存储根不可写");
        }
        available = true;
    }

    public boolean available() {
        return available;
    }

    /** 原子写入加密包；失败抛 1009S0003（不含内容/路径出站）。 */
    public void store(final String packageId, final byte[] envelope) {
        final Path target = resolve(packageId);
        Path temp = null;
        try {
            temp = Files.createTempFile(root, "delivery-", ".tmp");
            Files.write(temp, envelope);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            log.error("交付包写入失败: packageId={}", packageId, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (final IOException ignored) {
                    // best-effort：清理失败不影响判定
                }
            }
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE, e);
        }
    }

    /** 读取包路径（仅供流式下载）；缺失/不可读 → 1009S0003。 */
    public Path requirePath(final String packageId) {
        final Path path = resolve(packageId);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE);
        }
        return path;
    }

    /** 失败回滚（best-effort）。 */
    public void deleteQuietly(final String packageId) {
        try {
            Files.deleteIfExists(resolve(packageId));
        } catch (final IOException | RuntimeException e) {
            log.warn("交付包回滚删除失败: packageId={}", packageId);
        }
    }

    private Path resolve(final String packageId) {
        if (!available) {
            throw new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                    DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
        }
        if (packageId == null || !PACKAGE_ID.matcher(packageId).matches()) {
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE);
        }
        final Path path = root.resolve(packageId).normalize();
        if (!path.startsWith(root)) {
            throw new DeliveryBizException(DeliveryErrorCodes.PACKAGE_OPERATION_FAILED,
                    DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE);
        }
        return path;
    }
}
