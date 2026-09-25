package com.ctds.did.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 防回退守卫（WBS-3.1.11 hifi T17③，源集扫描可证伪）：
 * ① DID 服务源集不得出现任何密钥材料读取（SM2 材料路径 `requireDataKey` 仅 SM4 适用，ADR-017 §2.8）；
 * ② 演示签名入口出厂默认必须关闭（yml 占位符默认 `false`，源集不得硬编码开启）；
 * 反向探针：同一扫描口径对测试源集（含上述字样）必须命中，证明断言非空转。
 */
class DemoSignatureGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path TEST_JAVA = Path.of("src/test/java");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    @Test
    void mainSourceSetNeverTouchesKeyMaterial() throws IOException {
        final String mainSource = readJavaSources(MAIN_JAVA);

        assertThat(mainSource).doesNotContain("requireDataKey");
        assertThat(mainSource).doesNotContain("material_cipher");
        assertThat(mainSource).doesNotContain("privateKey");
        assertThat(mainSource).doesNotContain("PrivateKey");

        // 反向探针：测试源集（含守卫自身字样）经同一口径扫描必命中 → 扫描器有效
        final String testSource = readJavaSources(TEST_JAVA);
        assertThat(testSource).contains("requireDataKey").contains("privateKey");
    }

    @Test
    void demoSignatureEntryDefaultsToDisabled() throws IOException {
        final String yml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);

        assertThat(yml).contains("enabled: ${CTDS_DID_DEMO_SIGNATURE_ENABLED:false}");
        assertThat(yml).doesNotContain("${CTDS_DID_DEMO_SIGNATURE_ENABLED:true}");
        assertThat(readJavaSources(MAIN_JAVA)).doesNotContain("CTDS_DID_DEMO_SIGNATURE_ENABLED");
    }

    private static String readJavaSources(final Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(DemoSignatureGuardTest::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("源文件读取失败：" + path, e);
        }
    }
}
