package com.ctds.common.crypto;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 国密组件自动装配（模式同前四组件；契约 = docs/designs/WBS-2.4.6-hifi.md / ADR-006）。
 * ctds.crypto.enabled=false → 全部 Bean 不注册（fail-fast：使用方注入失败即启动失败，沿 2.4.5 教训）。
 */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
@ConditionalOnProperty(prefix = "ctds.crypto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CryptoAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CryptoAutoConfiguration.class);

    /** 密钥供给（Q3 模式 A）：本地文件实现；业务/2.6.3 定义 KeyProvider Bean 即整体替换。 */
    @Bean
    @ConditionalOnMissingBean(KeyProvider.class)
    public KeyProvider cryptoKeyProvider(final CryptoProperties properties) {
        final String keyFile = properties.getLocal().getKeyFile();
        if (keyFile == null || keyFile.isBlank()) {
            log.warn("ctds.crypto.local.key-file is not set: SM4 operations will fail with CRYPTO_KEY_UNAVAILABLE "
                    + "until a key source is provided (local file path or a KeyProvider bean)");
            return LocalFileKeyProvider.empty();
        }
        return LocalFileKeyProvider.fromFile(Path.of(keyFile));
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm4Service sm4Service(final KeyProvider keyProvider) {
        return new Sm4Service(keyProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm2Service sm2Service() {
        return new Sm2Service();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm3Service sm3Service() {
        return new Sm3Service();
    }
}
