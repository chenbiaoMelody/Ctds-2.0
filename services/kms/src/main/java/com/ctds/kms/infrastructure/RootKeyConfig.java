package com.ctds.kms.infrastructure;

import com.ctds.common.crypto.CryptoErrorCodes;
import com.ctds.common.crypto.KeyProvider;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KmsKeys;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 根密钥装配（WBS-2.6.3 hifi §3.4）：材料经环境变量注入（ctds.kms.root-key ← CTDS_KMS_ROOT_KEY），
 * 缺失/非 16 字节 = 启动失败 fail-fast（沿 2.4.6 密钥文件同款语义）。
 * 以 KeyProvider Bean 形式接入 common-crypto（ADR-006 契约），根密钥只在内存，禁入日志/异常消息。
 */
@Configuration
public class RootKeyConfig {

    private static final int SM4_KEY_BYTES = 16;

    @Bean
    public KeyProvider kmsRootKeyProvider(@Value("${ctds.kms.root-key:}") final String rootKeyBase64) {
        final byte[] rootKey = decodeRootKey(rootKeyBase64);
        return keyRef -> {
            if (!KmsKeys.ROOT_KEY_REF.equals(keyRef)) {
                throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return rootKey.clone();
        };
    }

    @Bean
    public Sm4Service kmsSm4Service(final KeyProvider kmsRootKeyProvider) {
        return new Sm4Service(kmsRootKeyProvider);
    }

    private static byte[] decodeRootKey(final String rootKeyBase64) {
        if (rootKeyBase64 == null || rootKeyBase64.isBlank()) {
            throw new IllegalStateException("CTDS_KMS_ROOT_KEY 未配置：KMS 服务拒绝启动（fail-fast）");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(rootKeyBase64.trim());
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("CTDS_KMS_ROOT_KEY 不是合法 Base64：KMS 服务拒绝启动（fail-fast）");
        }
        if (decoded.length != SM4_KEY_BYTES) {
            throw new IllegalStateException("CTDS_KMS_ROOT_KEY 必须为 Base64 编码的 16 字节：KMS 服务拒绝启动（fail-fast）");
        }
        return decoded;
    }
}
