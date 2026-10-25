package com.ctds.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.delivery.config.DeliveryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件源清单安全单测（hifi §9 T6）：fileId 精确键、路径必须落在源根内、
 * 拒绝符号链接/绝对路径/目录穿越、启动 fail-fast、请求时上限与产品归属。
 */
class FileSourceRegistryTest {

    @TempDir
    Path temp;

    @Test
    void validManifestLoadsAndResolves() throws Exception {
        Files.writeString(temp.resolve("a.csv"), "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        assertThat(registry.enabled()).isTrue();
        assertThat(registry.find("a")).isPresent();
        final FileSource source = registry.require("a", 13);
        assertThat(source.productId()).isEqualTo(13);
        assertThat(source.displayName()).isEqualTo("A.csv");
        assertThat(source.size()).isEqualTo(5);
    }

    @Test
    void duplicateFileIdFailsStartup() throws Exception {
        Files.writeString(temp.resolve("a.csv"), "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024,
                entry("a", 13, "a.csv", "A.csv"), entry("a", 13, "a.csv", "A2.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pathTraversalFailsStartup() {
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "../evil.csv", "A.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absolutePathFailsStartup() {
        final FileSourceRegistry registry = registry(1024,
                entry("a", 13, temp.resolve("a.csv").toAbsolutePath().toString(), "A.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingFileFailsStartup() {
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "missing.csv", "A.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maxBytesAboveCryptoLimitFailsStartup() {
        final FileSourceRegistry registry = registry(64L * 1024 * 1024 + 1);
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestTimeOversizeRejected() throws Exception {
        final Path file = temp.resolve("a.csv");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        Files.write(file, new byte[2048]);
        assertThatThrownBy(() -> registry.require("a", 13))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009C0008"));
    }

    @Test
    void exactLimitAcceptedAndOneByteOverRejected() throws Exception {
        final Path file = temp.resolve("a.csv");
        Files.write(file, new byte[1024]);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        assertThat(registry.require("a", 13).size()).isEqualTo(1024);
        Files.write(file, new byte[1025]);
        assertThatThrownBy(() -> registry.require("a", 13))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009C0008"));
    }

    /** DB-53①（TOCTOU）：启动后源文件被替换为符号链接（指向源根外），请求时复核 fail-closed。 */
    @Test
    void requestTimeSymlinkSwapRejected() throws Exception {
        final Path file = temp.resolve("a.csv");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        final Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.csv"), "leak", StandardCharsets.UTF_8);
        Files.deleteIfExists(file);
        try {
            Files.createSymbolicLink(file, outside.resolve("secret.csv"));
        } catch (final Exception e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
            return;
        }
        assertThatThrownBy(() -> registry.require("a", 13))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0002"));
    }

    @Test
    void cryptoHardLimitBoundaryAccepted() throws Exception {
        final long max = 64L * 1024 * 1024;
        Files.write(temp.resolve("max.csv"), new byte[(int) max]);
        final FileSourceRegistry registry = registry(max, entry("a", 13, "max.csv", "A.csv"));
        registry.load();
        assertThat(registry.require("a", 13).size()).isEqualTo(max);
    }

    @Test
    void uncPathFailsStartup() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "UNC 路径仅 Windows 可测");
        final FileSourceRegistry registry = registry(1024,
                entry("a", 13, "\\\\server\\share\\a.csv", "A.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void symbolicLinkFailsStartup() throws Exception {
        final Path target = temp.resolve("target.csv");
        Files.writeString(target, "hello", StandardCharsets.UTF_8);
        final Path link = temp.resolve("link.csv");
        try {
            Files.createSymbolicLink(link, target);
        } catch (final Exception e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
            return;
        }
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "link.csv", "A.csv"));
        assertThatThrownBy(registry::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productMismatchRejected() throws Exception {
        Files.writeString(temp.resolve("a.csv"), "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        assertThatThrownBy(() -> registry.require("a", 99))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009C0006"));
    }

    @Test
    void unknownFileRejected() throws Exception {
        Files.writeString(temp.resolve("a.csv"), "hello", StandardCharsets.UTF_8);
        final FileSourceRegistry registry = registry(1024, entry("a", 13, "a.csv", "A.csv"));
        registry.load();
        assertThatThrownBy(() -> registry.require("zzz", 13))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009C0006"));
    }

    @Test
    void disabledManifestFailsClosed() {
        final FileSourceRegistry registry = registry(1024);
        registry.load();
        assertThat(registry.enabled()).isFalse();
        assertThatThrownBy(() -> registry.require("a", 13))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0002"));
    }

    private FileSourceRegistry registry(final long maxBytes,
            final DeliveryProperties.FileEntry... entries) {
        final DeliveryProperties properties = new DeliveryProperties();
        properties.setSourceRoot(temp.toString());
        properties.setStorageRoot(temp.resolve("store").toString());
        properties.getFile().setMaxBytes(maxBytes);
        properties.getFile().setKeyRef("delivery-file");
        properties.setFiles(List.of(entries));
        return new FileSourceRegistry(properties);
    }

    private static DeliveryProperties.FileEntry entry(final String fileId, final long productId,
            final String path, final String displayName) {
        final DeliveryProperties.FileEntry entry = new DeliveryProperties.FileEntry();
        entry.setFileId(fileId);
        entry.setProductId(productId);
        entry.setPath(path);
        entry.setDisplayName(displayName);
        return entry;
    }
}
