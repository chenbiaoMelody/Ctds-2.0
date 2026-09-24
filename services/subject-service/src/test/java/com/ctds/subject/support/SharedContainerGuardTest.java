package com.ctds.subject.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DB-25 防回归守卫（ADR-010 §8 容器声明形态 B）：本模块测试源集必须始终"容器经 {@link SharedMySqlContainer}
 * 共享 + 逐类/逐用例独立库名"，不得回退为"每个测试类自起容器"。四条机器锚点：
 *
 * <ol>
 *   <li><b>不得声明容器字段注解</b>（{@code @Container}——简写/通配导入/全限定三种写法一律命中；本模块统一
 *       采用形态 B，比 §8 字面"禁类级"更严：共享容器 + 独立库名已覆盖方法级容器场景）；</li>
 *   <li><b>不得直接实例化容器</b>（{@code new MySQLContainer(...)}；唯一合法实例化点 = 支撑类自身）；</li>
 *   <li><b>库名互不重复</b>（{@code "ctds_*"} 字面量全模块去重）——把"独立库名 = 隔离载体"从一次性手工探针
 *       固化为可回归断言；</li>
 *   <li><b>使用共享容器的测试类必须带 {@code @Testcontainers(disabledWithoutDocker = true)}</b>
 *       （ADR-010 §8 约束①：无 Docker 跳过语义不得弱化）。</li>
 * </ol>
 *
 * <p>无 Docker 依赖：纯源码形态扫描，任何环境下都真实执行（不跳过，不空跑）；另设"扫描面必须覆盖到
 * 使用共享容器的测试类"断言，防扫描路径失效导致的假绿。</p>
 *
 * <p>自我排除：本守卫与支撑类自身的文件不在扫描面内（前者含检测用的字面量，后者是唯一合法实例化点）。</p>
 */
class SharedContainerGuardTest {

    /** 容器字段注解（简写形态；`@Container` 前可有空白/换行）。 */
    private static final Pattern CONTAINER_ANNOTATION = Pattern.compile("@Container\\b");

    /** 直接实例化容器（回退写法）。 */
    private static final Pattern CONTAINER_INSTANTIATION =
            Pattern.compile("new\\s+(MySQLContainer|GenericContainer|JdbcDatabaseContainer)\\s*<");

    /** 库名字面量（ADR-010 §8 形态 B：逐类/逐用例独立库名）。 */
    private static final Pattern DATABASE_LITERAL = Pattern.compile("\"(ctds_[a-z0-9_]{1,60})\"");

    private static final String SHARED_CONTAINER_REFERENCE = "SharedMySqlContainer";
    private static final String DISABLED_WITHOUT_DOCKER = "@Testcontainers(disabledWithoutDocker = true)";
    private static final String GUARD_FILE = "SharedContainerGuardTest.java";
    private static final String SUPPORT_FILE = "SharedMySqlContainer.java";

    @Test
    void testSourcesMustUseSharedContainerWithUniqueDatabases() throws IOException {
        final List<Path> sources = scanTestSources();
        final List<Path> sharedContainerUsers = sources.stream()
                .filter(source -> read(source).contains(SHARED_CONTAINER_REFERENCE))
                .toList();
        final List<String> databaseLiterals = sources.stream()
                .flatMap(source -> matches(DATABASE_LITERAL, read(source)).stream())
                .toList();

        assertThat(sharedContainerUsers)
                .as("扫描面必须覆盖使用共享容器的测试类（否则扫描路径失效 = 假绿）")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(databaseLiterals)
                .as("扫描面必须覆盖到独立库名字面量（否则库名去重断言空跑）")
                .isNotEmpty();

        final List<String> offenders = new ArrayList<>();
        final Map<String, String> databaseOwners = new LinkedHashMap<>();
        for (Path source : sources) {
            final String content = read(source);
            final String fileName = source.getFileName().toString();
            if (CONTAINER_ANNOTATION.matcher(content).find()) {
                offenders.add(fileName + "：声明了容器字段注解（应改用 " + SHARED_CONTAINER_REFERENCE + " 共享容器）");
            }
            if (!SUPPORT_FILE.equals(fileName) && CONTAINER_INSTANTIATION.matcher(content).find()) {
                offenders.add(fileName + "：直接实例化容器（唯一合法实例化点 = " + SUPPORT_FILE + "）");
            }
            if (content.contains(SHARED_CONTAINER_REFERENCE) && !SUPPORT_FILE.equals(fileName)
                    && !content.contains(DISABLED_WITHOUT_DOCKER)) {
                offenders.add(fileName + "：使用共享容器但缺少 " + DISABLED_WITHOUT_DOCKER + "（无 Docker 跳过语义）");
            }
            final Matcher matcher = DATABASE_LITERAL.matcher(content);
            while (matcher.find()) {
                final String database = matcher.group(1);
                final String previous = databaseOwners.putIfAbsent(database, fileName);
                if (previous != null) {
                    offenders.add(fileName + "：库名 " + database + " 与 " + previous + " 重复（跨类串扰风险）");
                }
            }
        }
        assertThat(offenders)
                .as("ADR-010 §8 形态 B 约束被破坏（共享容器 + 独立库名 + 无 Docker 跳过语义）")
                .isEmpty();
    }

    private static List<Path> scanTestSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src", "test", "java"))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !GUARD_FILE.equals(path.getFileName().toString()))
                    .toList();
        }
    }

    private static List<String> matches(final Pattern pattern, final String content) {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试源文件失败：" + source, e);
        }
    }
}
