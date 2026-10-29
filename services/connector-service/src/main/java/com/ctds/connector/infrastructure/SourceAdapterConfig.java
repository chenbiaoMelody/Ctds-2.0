package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorSourceRepository;
import com.ctds.connector.domain.SourceHostGuard;
import com.ctds.connector.sdk.adapter.apigateway.ApiGatewaySourceAdapter;
import com.ctds.connector.sdk.adapter.database.DatabaseSourceAdapter;
import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.adapter.gov.GovExchangeSourceAdapter;
import com.ctds.connector.sdk.adapter.objectstorage.ObjectStorageSourceAdapter;
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
        // 启动校验（hifi §12）：超时 1s~30s；越界 = 配置错误，启动即失败而非运行期才暴露
        if (adapterTimeout == null || adapterTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || adapterTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "ctds.connector.source.adapter-timeout 须在 1s~30s 之间，实际 = " + adapterTimeout);
        }
        return new GovExchangeSourceAdapter(credentialResolver, adapterTimeout);
    }

    /** 数据库适配器（WBS-3.6.6）：JDBC 只读探针 + 参数白名单；驱动由运行宿主提供。 */
    @Bean
    public SourceAdapter databaseSourceAdapter(final CredentialResolver credentialResolver) {
        return new DatabaseSourceAdapter(credentialResolver);
    }

    /** 对象存储适配器（WBS-3.6.6）：预签名 URL 只读 GET；不做签名实现。 */
    @Bean
    public SourceAdapter objectStorageSourceAdapter(final CredentialResolver credentialResolver) {
        return new ObjectStorageSourceAdapter(credentialResolver);
    }

    /** API 网关适配器（WBS-3.6.6）：GET 只读；Authorization 原样出站。 */
    @Bean
    public SourceAdapter apiGatewaySourceAdapter(final CredentialResolver credentialResolver) {
        return new ApiGatewaySourceAdapter(credentialResolver);
    }

    /**
     * 调用边界主机防护（DB-91）：默认拒绝回环 / 内网 / 链路本地；测试与演示显式放开，
     * 解析失败始终拒绝（无法证明目标安全）。
     */
    @Bean
    public SourceHostGuard sourceHostGuard(
            @Value("${ctds.connector.source.allow-private-endpoint:false}")
            final boolean allowPrivateEndpoint) {
        return new InetAddressSourceHostGuard(allowPrivateEndpoint);
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
