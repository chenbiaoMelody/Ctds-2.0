package com.ctds.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.FileSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 沙箱工作区单测（hifi §5 / §9 数据面组）：输入按 fileId 复制（不采用展示名作路径）、
 * manifest 不含宿主机路径、文件数与总字节上限 fail-closed、结果只出元数据（SM3）。
 */
class SandboxWorkspaceStoreTest {

    private static final String SANDBOX_NO = "SB000001";

    @TempDir
    Path temp;

    private Path sourceDir;
    private SandboxProperties properties;
    private SandboxWorkspaceStore store;
    private final Sm3Service sm3 = new Sm3Service();

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectories(temp.resolve("source"));
        properties = new SandboxProperties();
        properties.setWorkspaceRoot(temp.resolve("workspace").toString());
        properties.setJobsRoot(temp.resolve("jobs").toString());
        properties.getQuota().setMaxInputFiles(2);
        properties.getQuota().setMaxInputBytes(1024);
        properties.getRuntime().setEnabled(true);
        store = new SandboxWorkspaceStore(properties, sm3);
    }

    @Test
    void prepareCopiesInputsByFileIdAndWritesManifestWithoutHostPaths() throws Exception {
        final Path data = Files.createDirectories(sourceDir.resolve("SB000001"));
        Files.writeString(data.resolve("a.csv"), "alpha", StandardCharsets.UTF_8);
        final Path root = store.prepare(SANDBOX_NO,
                List.of(new FileSource("a", 13, data.resolve("a.csv"), "客户清单.csv", 5)));

        assertThat(root.resolve("input").resolve("a")).exists();
        assertThat(Files.readString(root.resolve("input").resolve("a"))).isEqualTo("alpha");
        assertThat(root.resolve("output")).isDirectory();
        final String manifest = Files.readString(root.resolve("input").resolve("manifest.tsv"));
        assertThat(manifest).contains("a", "客户清单.csv", "5");
        assertThat(manifest).doesNotContain(temp.toString(), sourceDir.toString(), "SB000001");
    }

    @Test
    void tooManyInputFilesFailsClosed() throws Exception {
        final List<FileSource> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Path file = sourceDir.resolve("f" + i);
            Files.writeString(file, "x", StandardCharsets.UTF_8);
            files.add(new FileSource("f" + i, 13, file, "F" + i, 1));
        }
        assertThatThrownBy(() -> store.prepare(SANDBOX_NO, files))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0004"));
    }

    @Test
    void totalBytesAboveLimitFailsClosed() throws Exception {
        final Path big = sourceDir.resolve("big.bin");
        Files.write(big, new byte[1025]);
        assertThatThrownBy(() -> store.prepare(SANDBOX_NO,
                List.of(new FileSource("big", 13, big, "big.bin", 1025))))
                .isInstanceOfSatisfying(DeliveryBizException.class,
                        ex -> assertThat(ex.getErrorCode().value()).isEqualTo("1009S0004"));
    }

    @Test
    void resultMetadataExposesOnlyTopLevelRegularFiles() throws Exception {
        final Path root = store.prepare(SANDBOX_NO, List.of());
        final Path output = root.resolve("output");
        Files.writeString(output.resolve("profile.md"), "result", StandardCharsets.UTF_8);
        Files.createDirectories(output.resolve("nested"));
        Files.writeString(output.resolve("nested").resolve("hidden.md"), "x", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("bad name.md"), "x", StandardCharsets.UTF_8);

        final List<SandboxWorkspaceStore.ResultFile> results = store.listResults(SANDBOX_NO);
        assertThat(results).hasSize(1);
        final SandboxWorkspaceStore.ResultFile file = results.get(0);
        assertThat(file.fileName()).isEqualTo("profile.md");
        assertThat(file.sizeBytes()).isEqualTo(6);
        assertThat(file.checksum()).isEqualTo(sm3.digestHex("result"));
    }

    @Test
    void missingWorkspaceReturnsEmptyResults() {
        assertThat(store.listResults(SANDBOX_NO)).isEmpty();
    }

    @Test
    void deleteIsIdempotentAndInvalidSandboxNoRejected() throws Exception {
        store.prepare(SANDBOX_NO, List.of());
        store.deleteQuietly(SANDBOX_NO);
        store.deleteQuietly(SANDBOX_NO);
        assertThat(temp.resolve("workspace").resolve(SANDBOX_NO)).doesNotExist();
        assertThatThrownBy(() -> store.prepare("../escape", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
