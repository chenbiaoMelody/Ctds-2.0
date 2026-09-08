package com.ctds.example.infrastructure;

import com.ctds.common.crypto.CryptoErrorCodes;
import com.ctds.common.crypto.KeyProvider;
import com.ctds.common.errorcode.BizException;
import com.ctds.example.application.SecretNoteService;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 演示密钥供给（模式 A 的"覆盖点"实例）：定义 KeyProvider Bean 即整体替换组件默认本地文件实现——
 * 真实 KMS（2.6.3）届时同样以 Bean 覆盖，业务代码零改动。
 * 演示密钥启动时随机生成、只在内存、服务重启即更换（红线：任何密钥不入库；本演示无持久化存储）。
 */
@Configuration
public class DemoKeyProviderConfig {

    private static final int SM4_KEY_BYTES = 16;

    @Bean
    public KeyProvider demoKeyProvider() {
        final byte[] demoKey = new byte[SM4_KEY_BYTES];
        new SecureRandom().nextBytes(demoKey);
        return keyRef -> {
            if (!SecretNoteService.KEY_REF.equals(keyRef)) {
                throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return demoKey.clone();
        };
    }
}
