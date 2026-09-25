package com.ctds.did.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.did.application.DidInteropService;
import com.ctds.std.did.DidInteropStandardApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 收口守卫（WBS-3.1.10 hifi §7 T11，ADR-008 §3.1/§3.5）：互认协议实现唯一落点 = std-adapter 的 did 域。
 * <p>双向可证伪：did 服务源集出现具体实现类名即红；互认链路仅经 {@link DidInteropStandardApi} 接口类型取用。</p>
 */
class InteropSeamTest {

    private static final String IMPLEMENTATION_CLASS = "MockDidInteropStandardApi";

    /** did 域包名前缀（互认域反向耦合判定口径）。 */
    private static final String DID_PACKAGE = "com.ctds.did.";

    @Test
    void 互认协议实现不出现在did服务源集() throws IOException {
        final Path mainSources = Path.of("src", "main", "java");
        assertThat(mainSources).as("以模块目录为工作目录运行测试").exists();

        final List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            final List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            for (final Path file : javaFiles) {
                if (read(file).contains(IMPLEMENTATION_CLASS)) {
                    hits.add(file.toString());
                }
            }
        }

        assertThat(hits).as("互认协议实现必须收口 std-adapter，业务服务不得依赖具体实现类").isEmpty();
    }

    @Test
    void 互认应用服务仅依赖互认域接口类型() {
        final boolean dependsOnInterface = Arrays.stream(DidInteropService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(type -> type.equals(DidInteropStandardApi.class));

        assertThat(dependsOnInterface).as("DidInteropService 经接口类型取得互认能力").isTrue();
    }

    @Test
    void 互认域实现源集不得依赖did域具体类型() throws IOException {
        // WBS-3.1.12 T3（反向守卫）：ADR-008 §3.1 收口是"互认域不得反向耦合业务域"，原守卫只覆盖 did→std 单向。
        final Path interopSources =
                Path.of("..", "..", "std-adapter", "src", "main", "java", "com", "ctds", "std", "did");
        assertThat(interopSources).as("以模块目录（services/did）为工作目录运行测试，std-adapter 位于仓库根").exists();

        final List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(interopSources)) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (referencesDidDomain(read(file))) {
                    hits.add(file.toString());
                }
            }
        }

        assertThat(hits).as("互认域实现不得引用 did 域具体类型（%s）", DID_PACKAGE).isEmpty();
        // 反向探针：同一判定口径对违规样本必命中（证明扫描非空转）
        assertThat(referencesDidDomain("import " + DID_PACKAGE + "domain.DidIdentity;"))
                .as("反向探针：引用 did 域类型的样本必须被判为违规").isTrue();
    }

    /** 互认域源集引用 did 域具体类型即违规（判据 = did 域包名前缀）。 */
    private static boolean referencesDidDomain(final String source) {
        return source.contains(DID_PACKAGE);
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
