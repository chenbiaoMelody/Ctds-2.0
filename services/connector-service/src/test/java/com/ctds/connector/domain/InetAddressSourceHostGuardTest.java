package com.ctds.connector.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.infrastructure.InetAddressSourceHostGuard;
import com.ctds.connector.sdk.spi.SourceType;
import org.junit.jupiter.api.Test;

/**
 * 解析级主机防护（WBS-3.6.6 hifi §9 T5 / DB-91）：默认拒绝回环 / 内网 / 链路本地 / IPv6 唯一本地 /
 * 不可解析主机；放行开关只对"已解析但属受限网段"生效，解析失败始终拒绝。
 *
 * <p>纯单元用例（不依赖容器）：覆盖服务端点用例无法构造的私网字面量（受理期字面量规则会先拒绝）。</p>
 */
class InetAddressSourceHostGuardTest {

    private final SourceHostGuard strict = new InetAddressSourceHostGuard(false);
    private final SourceHostGuard permissive = new InetAddressSourceHostGuard(true);

    private static void assertRejected(final SourceHostGuard guard, final SourceType type,
            final String endpoint, final String credential) {
        assertThatThrownBy(() -> guard.assertAllowed(type, endpoint, credential))
                .isInstanceOf(ConnectorBizException.class)
                .hasMessage("源地址不被允许（解析到受限网络或访问形态不支持）");
    }

    @Test
    void rejectsLoopbackAndPrivateIpv4() {
        assertRejected(strict, SourceType.API_GATEWAY, "http://127.0.0.1/api", null);
        assertRejected(strict, SourceType.API_GATEWAY, "http://10.12.34.56/api", null);
        assertRejected(strict, SourceType.API_GATEWAY, "http://192.168.1.10/api", null);
        assertRejected(strict, SourceType.API_GATEWAY, "http://169.254.169.254/latest", null);
    }

    @Test
    void rejectsLoopbackAndUniqueLocalIpv6() {
        assertRejected(strict, SourceType.API_GATEWAY, "http://[::1]/api", null);
        assertRejected(strict, SourceType.API_GATEWAY, "http://[fc00::1]/api", null);
    }

    @Test
    void rejectsUnresolvableHostEvenWhenPrivateEndpointsAllowed() {
        assertRejected(strict, SourceType.API_GATEWAY, "http://no-such-host.invalid/api", null);
        assertRejected(permissive, SourceType.API_GATEWAY, "http://no-such-host.invalid/api", null);
    }

    @Test
    void databaseHostComesFromJdbcUrl() {
        assertRejected(strict, SourceType.DATABASE, "jdbc:mysql://10.12.34.56:3306/dataset",
                "user:password");
        assertRejected(strict, SourceType.DATABASE, "jdbc:mysql://[fd00::2]:3306/dataset",
                "user:password");
    }

    @Test
    void objectStorageCredentialUrlHostIsAlsoGuarded() {
        assertRejected(strict, SourceType.OBJECT_STORAGE, "oss://dataset-bucket/object",
                "http://127.0.0.1:9000/bucket/object?X-Amz-Signature=test");
    }

    @Test
    void permissiveSwitchAllowsResolvedPrivateHostOnly() {
        assertThatCode(() -> permissive.assertAllowed(SourceType.API_GATEWAY,
                "http://127.0.0.1:8080/api", null)).doesNotThrowAnyException();
        assertThatCode(() -> strict.assertAllowed(SourceType.API_GATEWAY,
                "http://8.8.8.8/api", null)).doesNotThrowAnyException();
    }

    @Test
    void objectStorageNativeSchemeHasNoNetworkTarget() {
        assertThatCode(() -> strict.assertAllowed(SourceType.OBJECT_STORAGE,
                "s3://dataset-bucket/object", null)).doesNotThrowAnyException();
    }
}
