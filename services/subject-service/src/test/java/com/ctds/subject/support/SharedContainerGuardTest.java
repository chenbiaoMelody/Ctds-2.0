package com.ctds.subject.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DB-25 防回归守卫（ADR-010 §8 容器声明形态 B）：本模块测试源集<b>不得</b>再自行声明类级容器字段——
 * 容器一律经 {@link SharedMySqlContainer} 共享 + 逐类/逐用例独立库名。
 *
 * <p>理由（DB-23 同族教训）：修复若无自动化锚点，将来有人加回"每个测试类一个容器"，
 * 测试时长增长只在贴门禁上限（600s）时暴露——平时静默。本守卫使该回退立刻红灯。</p>
 *
 * <p>无 Docker 依赖：纯源码形态扫描，任何环境下都真实执行（不跳过，不空跑）。</p>
 */
class SharedContainerGuardTest {

    /** 类级容器声明标注的全限定名（扫描信号=其 import 语句或全限定用法）。 */
    private static final String CONTAINER_ANNOTATION = "org.testcontainers.junit.jupiter.Container";

    private static final String CONTAINER_IMPORT = "import " + CONTAINER_ANNOTATION + ";";

    @Test
    void testSourcesMustNotDeclareOwnContainerFields() throws IOException {
        final List<Path> sources;
        try (Stream<Path> paths = Files.walk(Path.of("src", "test", "java"))) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(sources)
                .as("测试源集扫描面不得为空（防空跑假绿）")
                .hasSizeGreaterThan(5);
        final List<String> offenders = sources.stream()
                .filter(SharedContainerGuardTest::declaresOwnContainer)
                .map(Path::toString)
                .toList();
        assertThat(offenders)
                .as("测试类不得自行声明容器字段（ADR-010 §8 形态 B）：请改用 SharedMySqlContainer + 独立库名")
                .isEmpty();
    }

    private static boolean declaresOwnContainer(final Path source) {
        final String content;
        try {
            content = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试源文件失败：" + source, e);
        }
        return content.contains(CONTAINER_IMPORT) || content.contains("@" + CONTAINER_ANNOTATION);
    }
}
