package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B7：本地密钥文件加载/只读语义/坏配置 fail-fast（报错不含密钥值，红线 B10）/未知编号 KEY_UNAVAILABLE。
 */
class LocalFileKeyProviderTest {

    private static final String B64_A = Base64.getEncoder().encodeToString(TestKeys.KEY_16);
    private static final String B64_B = Base64.getEncoder().encodeToString(TestKeys.OTHER_KEY_16);

    @TempDir
    Path dir;

    private LocalFileKeyProvider load(final String content) throws Exception {
        final Path file = dir.resolve("keys-" + System.nanoTime() + ".properties");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return LocalFileKeyProvider.fromFile(file);
    }

    @Test
    void loadsValidFileWithCommentsAndBlankLines() throws Exception {
        final LocalFileKeyProvider provider =
                load("# 演示密钥\n\ntest-key = " + B64_A + "\nother=" + B64_B + "\n");
        assertThat(provider.sm4Key("test-key")).isEqualTo(TestKeys.KEY_16);
        assertThat(provider.sm4Key("other")).isEqualTo(TestKeys.OTHER_KEY_16);
    }

    @Test
    void returnedKeysAreCopies() throws Exception {
        final LocalFileKeyProvider provider = load("test-key=" + B64_A + "\n");
        final byte[] first = provider.sm4Key("test-key");
        first[0] = 0;
        assertThat(provider.sm4Key("test-key")).isEqualTo(TestKeys.KEY_16);
    }

    @Test
    void unknownRefThrowsKeyUnavailable() throws Exception {
        final LocalFileKeyProvider provider = load("test-key=" + B64_A + "\n");
        assertThatThrownBy(() -> provider.sm4Key("missing"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }

    @Test
    void malformedRowsFailFastWithoutLeakingValues() {
        final String notBase64 = "!!not-valid-base64!!";
        assertThatThrownBy(() -> load("no-equals-sign\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("第1行");
        assertThatThrownBy(() -> load("bad-key=" + notBase64 + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getMessage())
                        .contains("第1行", "Base64", "bad-key") // 编号（业务别名）可出现
                        .doesNotContain(notBase64)); // 值不可出现（红线）
        final String shortValue = Base64.getEncoder().encodeToString(new byte[15]);
        assertThatThrownBy(() -> load("short=" + shortValue + "\n"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getMessage()).contains("16 字节").doesNotContain(shortValue));
        assertThatThrownBy(() -> load("dup=" + B64_A + "\ndup=" + B64_B + "\n"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复");
    }

    @Test
    void missingFileFailsFastWithClearReason() {
        assertThatThrownBy(() -> LocalFileKeyProvider.fromFile(dir.resolve("nope.properties")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或不可读");
    }

    @Test
    void emptyProviderAlwaysThrows() {
        assertThatThrownBy(() -> LocalFileKeyProvider.empty().sm4Key("any"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }

    @Test
    void providerNeverWritesBackFile() throws Exception {
        final Path file = dir.resolve("immutable.properties");
        Files.writeString(file, "test-key=" + B64_A + "\n", StandardCharsets.UTF_8);
        final byte[] before = Files.readAllBytes(file);
        final long modifiedBefore = Files.getLastModifiedTime(file).toMillis();
        LocalFileKeyProvider.fromFile(file).sm4Key("test-key");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modifiedBefore);
    }
}
