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

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
