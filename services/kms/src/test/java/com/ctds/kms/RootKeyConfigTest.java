package com.ctds.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.crypto.CryptoErrorCodes;
import com.ctds.common.crypto.KeyProvider;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.infrastructure.RootKeyConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 根密钥装配面（hifi §3.4）：缺失/坏 Base64/长度不对 = 启动失败 fail-fast；
 * 合法配置下根密钥只经信封取用别名供给、未知别名拒绝（密钥材料不落任何出站面）。
 */
class RootKeyConfigTest {

    private static final String TEST_ROOT_MATERIAL_B64 =
            Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RootKeyConfig.class);

    @Test
    void missingRootKeyFailsStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CTDS_KMS_ROOT_KEY 未配置");
        });
    }

    @Test
    void badBase64FailsStartup() {
        runner.withPropertyValues("ctds.kms.root-key=not-base64!!").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("合法 Base64");
        });
    }

    @Test
    void wrongLengthFailsStartup() {
        runner.withPropertyValues("ctds.kms.root-key=" + Base64.getEncoder().encodeToString(new byte[8]))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("16 字节");
                });
    }

    @Test
    void validRootKeySuppliesOnlyViaDedicatedAlias() {
        runner.withPropertyValues("ctds.kms.root-key=" + TEST_ROOT_MATERIAL_B64).run(context -> {
            assertThat(context).hasNotFailed();
            final KeyProvider provider = context.getBean(KeyProvider.class);
            assertThat(provider.sm4Key(com.ctds.kms.domain.KmsKeys.ROOT_KEY_REF))
                    .isEqualTo("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> provider.sm4Key("anything-else"))
                    .isInstanceOfSatisfying(BizException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
        });
    }

    @Test
    void beanCreationFailureCarriesReason() {
        // fail-fast 顶层消息可读（沿 2.4.6 配置坏 = 启动失败并指明原因的验收口径）
        runner.withPropertyValues("ctds.kms.root-key=").run(context ->
                assertThat(context.getStartupFailure()).isInstanceOf(BeanCreationException.class));
    }
}
