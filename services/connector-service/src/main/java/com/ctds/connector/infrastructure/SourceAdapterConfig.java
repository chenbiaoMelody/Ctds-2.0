package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorSourceRepository;
import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.adapter.gov.GovExchangeSourceAdapter;
import com.ctds.connector.sdk.spi.SourceAdapter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 适配器装配（hifi §8"入站装配 1 处"）：本期仅政务共享交换适配器；3.6.6 在此追加三类通用源实现即可。
 *
 * <p>{@link CredentialResolver} 由平台注入：按凭据引用定位密文并解密（明文只在适配器调用期于内存流转，
 * 不落日志、不入登记模型、不回显响应）。</p>
 */
@Configuration
public class SourceAdapterConfig {

    @Bean
    public SourceAdapter govExchangeSourceAdapter(final CredentialResolver credentialResolver,
            @Value("${ctds.connector.source.adapter-timeout:3s}") final Duration adapterTimeout) {
        return new GovExchangeSourceAdapter(credentialResolver, adapterTimeout);
    }

    @Bean
    public CredentialResolver credentialResolver(final CredentialCipher cipher,
            final ConnectorSourceRepository sourceRepository) {
        return credentialRef -> cipher.decrypt(sourceRepository.findByCredentialRef(credentialRef)
                .orElseThrow(() -> new ConnectorBizException(ConnectorErrorCodes.NOT_FOUND_OR_DENIED,
                        ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE))
                .credentialCiphertext());
    }
}
