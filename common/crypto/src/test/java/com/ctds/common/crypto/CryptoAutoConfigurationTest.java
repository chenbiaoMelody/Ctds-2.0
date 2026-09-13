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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * B7/B10 装配面：默认开启、fail-fast（key-file 配置了但缺失 → 启动失败）、
 * enabled=false → Bean 全不注册（使用方注入失败）、KeyProvider Bean 覆盖（2.6.3 KMS 接入路径）。
 */
class CryptoAutoConfigurationTest {

    @TempDir
    Path dir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration.class));

    private Path writeKeyFile() throws Exception {
        final Path file = dir.resolve("keys.properties");
        final String b64 = Base64.getEncoder().encodeToString(TestKeys.KEY_16);
        Files.writeString(file, "# test\n" + TestKeys.KEY_REF + "=" + b64 + "\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void defaultsRegisterAllFourBeansAndWorkWithKeyFile() throws Exception {
        final Path file = writeKeyFile();
        runner.withPropertyValues("ctds.crypto.local.key-file=" + file).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Sm4Service.class);
            assertThat(context).hasSingleBean(Sm2Service.class);
            assertThat(context).hasSingleBean(Sm3Service.class);
            assertThat(context).hasSingleBean(KeyProvider.class);
            final Sm4Service sm4 = context.getBean(Sm4Service.class);
            assertThat(sm4.decrypt(sm4.encrypt("hi".getBytes(StandardCharsets.UTF_8), TestKeys.KEY_REF),
                    TestKeys.KEY_REF)).isEqualTo("hi".getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void noKeyFileConfiguredStartsButSm4FailsWithKeyUnavailable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final Sm4Service sm4 = context.getBean(Sm4Service.class);
            assertThatThrownBy(() -> sm4.encrypt(new byte[]{1}, TestKeys.KEY_REF))
                    .isInstanceOfSatisfying(BizException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
            // SM2/SM3 不依赖 KeyProvider，仍可用
            assertThat(context.getBean(Sm3Service.class).digestHex("abc"))
                    .isEqualTo("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");
        });
    }

    @Test
    void configuredButMissingKeyFileFailsFast() {
        runner.withPropertyValues("ctds.crypto.local.key-file=" + dir.resolve("nope.properties"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    // fail-fast 且给出明确原因（BeanCreation→Instantiation→ISE 包装链，顶层消息已含原因）
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("SM4 密钥文件不存在或不可读");
                });
    }

    @Test
    void disabledSwitchRegistersNoBeans() {
        runner.withPropertyValues("ctds.crypto.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(Sm4Service.class);
            assertThat(context).doesNotHaveBean(Sm2Service.class);
            assertThat(context).doesNotHaveBean(Sm3Service.class);
            assertThat(context).doesNotHaveBean(KeyProvider.class);
        });
    }

    @Test
    void customKeyProviderBeanOverridesLocalImplementation() {
        runner.withBean(KeyProvider.class, () -> keyRef -> {
                    if (!"kms-key".equals(keyRef)) {
                        throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
                    }
                    return TestKeys.OTHER_KEY_16.clone();
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(KeyProvider.class);
                    assertThat(context.getBean(KeyProvider.class)).isNotInstanceOf(LocalFileKeyProvider.class);
                    final Sm4Service sm4 = context.getBean(Sm4Service.class);
                    assertThat(sm4.decrypt(sm4.encrypt("via-kms".getBytes(StandardCharsets.UTF_8), "kms-key"),
                            "kms-key")).isEqualTo("via-kms".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void kmsBaseUrlRegistersKmsKeyProviderOverridingLocal() {
        // 指向无服务端口：装配成立、fail-fast 不降级（SM4 操作报 1001S0001）
        runner.withPropertyValues("ctds.crypto.kms.base-url=http://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyProvider.class)).isInstanceOf(KmsKeyProvider.class);
            final Sm4Service sm4 = context.getBean(Sm4Service.class);
            assertThatThrownBy(() -> sm4.encrypt(new byte[]{1}, TestKeys.KEY_REF))
                    .isInstanceOfSatisfying(BizException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
        });
    }

    @Test
    void kmsConfigWinsOverLocalKeyFile() {
        runner.withPropertyValues(
                        "ctds.crypto.local.key-file=" + dir.resolve("ignored.properties"),
                        "ctds.crypto.kms.base-url=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KeyProvider.class)).isInstanceOf(KmsKeyProvider.class);
                    assertThat(context).doesNotHaveBean(LocalFileKeyProvider.class);
                });
    }

    @Test
    void kmsDurationBindingAcceptsPlainSeconds() {
        // 纯数字按秒解释（@DurationUnit），防毫秒静默失效
        runner.withPropertyValues(
                        "ctds.crypto.kms.base-url=http://127.0.0.1:1",
                        "ctds.crypto.kms.connect-timeout=2",
                        "ctds.crypto.kms.read-timeout=4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CryptoProperties.Kms kms = context.getBean(CryptoProperties.class).getKms();
                    assertThat(kms.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
                    assertThat(kms.getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(4));
                });
    }
}
