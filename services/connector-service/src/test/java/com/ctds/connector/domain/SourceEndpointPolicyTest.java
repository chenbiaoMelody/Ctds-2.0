package com.ctds.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.sdk.spi.SourceType;
import org.junit.jupiter.api.Test;

/**
 * 源地址受理规则单元测试（WBS-3.6.5 评审循环 1 修复批：安全加固）：
 * scheme 白名单、禁止 URL 内嵌凭据、默认拒绝回环 / 内网 / 链路本地地址、留痕脱敏。
 *
 * <p>纯函数用例（不启 Spring / 不连库），覆盖 hifi §7-⑧ 的每条拒绝面与放行面。</p>
 */
class SourceEndpointPolicyTest {

    private static final String GOV_OK = "mock://gov-exchange/ok";
    private static final String HTTPS_OK = "https://source.example.com/dataset-1";

    @Test
    void acceptsGovMockAndHttpsEndpoints() {
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, GOV_OK, false)).isEqualTo(GOV_OK);
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, HTTPS_OK, false))
                .isEqualTo(HTTPS_OK);
        assertThat(SourceEndpointPolicy.normalize(SourceType.DATABASE, "http://db.example.com:8080/x", false))
                .isEqualTo("http://db.example.com:8080/x");
    }

    @Test
    void trimsSurroundingWhitespaceButRejectsInnerWhitespace() {
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, "  " + GOV_OK + "  ", false))
                .isEqualTo(GOV_OK);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://source.example.com/a b", false)).isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsNullBlankAndOversizeEndpoint() {
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, null, false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, "   ", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://source.example.com/" + "a".repeat(500), false))
                .isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsNonWhitelistedSchemes() {
        for (final String endpoint : new String[] {"file:///etc/passwd", "ftp://source.example.com/x",
                "source.example.com/x", "jdbc:mysql://db.example.com:3306/x"}) {
            assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.API_GATEWAY, endpoint, false))
                    .as(endpoint)
                    .isInstanceOf(ConnectorBizException.class);
        }
    }

    @Test
    void acceptsNativeSchemesForDatabaseAndObjectStorage() {
        assertThat(SourceEndpointPolicy.normalize(SourceType.DATABASE,
                "jdbc:mysql://db.example.com:3306/dataset", false))
                .isEqualTo("jdbc:mysql://db.example.com:3306/dataset");
        assertThat(SourceEndpointPolicy.normalize(SourceType.OBJECT_STORAGE, "oss://bucket/dataset", false))
                .isEqualTo("oss://bucket/dataset");
        assertThat(SourceEndpointPolicy.normalize(SourceType.OBJECT_STORAGE, "s3://bucket/dataset", false))
                .isEqualTo("s3://bucket/dataset");
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.API_GATEWAY,
                "oss://bucket/dataset", false)).isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsUserInfoInOpaqueJdbcUri() {
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.DATABASE,
                "jdbc:mysql://user:pass@db.example.com:3306/dataset", false))
                .isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsUrlEmbeddedCredential() {
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://svc:Pa55w0rd@gov.example.com/api", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://token@gov.example.com/api", false))
                .isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsCredentialLikeQueryKeys() {
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://gov.example.com/api?password=Pa55w0rd", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://gov.example.com/api?token=abc123", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://gov.example.com/api?SECRET=y", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.DATABASE,
                "jdbc:mysql://db.example.com:3306/dataset?password=p", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.API_GATEWAY,
                "https://gw.example.com/x?api_key=z", false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "mock://gov-exchange/ok?token=abc", false))
                .isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void acceptsInnocuousQueryKeys() {
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "https://gov.example.com/api?charset=utf8&pageSize=10", false))
                .isEqualTo("https://gov.example.com/api?charset=utf8&pageSize=10");
        assertThat(SourceEndpointPolicy.normalize(SourceType.DATABASE,
                "jdbc:mysql://db.example.com:3306/dataset?useSSL=false&characterEncoding=utf8", false))
                .isEqualTo("jdbc:mysql://db.example.com:3306/dataset?useSSL=false&characterEncoding=utf8");
    }

    @Test
    void rejectsEncodedPrivateHostForms() {
        for (final String endpoint : new String[] {"http://[::ffff:127.0.0.1]/x",
                "http://[::ffff:7f00:1]/x", "http://010.1.2.3/x", "http://127.1/x",
                "http://172.016.0.1/x", "http://192.168.001.5/x"}) {
            assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, endpoint, false))
                    .as(endpoint)
                    .isInstanceOf(ConnectorBizException.class);
        }
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "http://[::ffff:8.8.8.8]/x", false)).isEqualTo("http://[::ffff:8.8.8.8]/x");
    }

    @Test
    void mockSchemeIsGovExchangeOnly() {
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.DATABASE, GOV_OK, false))
                .isInstanceOf(ConnectorBizException.class);
        assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE,
                "mock://other-source/ok", false))
                .isInstanceOf(ConnectorBizException.class);
    }

    @Test
    void rejectsLoopbackPrivateAndLinkLocalHostsByDefault() {
        for (final String endpoint : new String[] {"http://127.0.0.1:9/x", "http://localhost:9/x",
                "http://10.1.2.3/x", "http://192.168.1.5/x", "http://172.20.0.1/x",
                "http://169.254.169.254/latest/meta-data", "http://[::1]:9/x", "http://2130706433/x"}) {
            assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, endpoint, false))
                    .as(endpoint)
                    .isInstanceOf(ConnectorBizException.class);
        }
    }

    @Test
    void allowsPrivateHostOnlyWhenExplicitlyEnabled() {
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, "http://127.0.0.1:9/x", true))
                .isEqualTo("http://127.0.0.1:9/x");
        assertThat(SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, "http://172.20.0.1/x", true))
                .isEqualTo("http://172.20.0.1/x");
    }

    @Test
    void redactStripsQueryAndFragment() {
        assertThat(SourceEndpointPolicy.redact("https://gov.example.com/api?token=abc#frag"))
                .isEqualTo("https://gov.example.com/api");
        assertThat(SourceEndpointPolicy.redact(GOV_OK)).isEqualTo(GOV_OK);
        assertThat(SourceEndpointPolicy.redact(null)).isEqualTo("<redacted>");
    }

    @Test
    void redactNeutralizesAuditFieldSeparator() {
        assertThat(SourceEndpointPolicy.redact("https://gov.example.com/a;b=c"))
                .isEqualTo("https://gov.example.com/a%3Bb=c");
    }

    @Test
    void redactStripsUserInfoFromLegacyEndpoint() {
        assertThat(SourceEndpointPolicy.redact("https://svc:Pa55w0rd@gov.example.com/api"))
                .isEqualTo("https://gov.example.com/api");
        assertThat(SourceEndpointPolicy.redact("jdbc:mysql://root:Pa55w0rd@db.example.com:3306/d"))
                .isEqualTo("<redacted>");
    }

    @Test
    void rejectsUnparseableUriAndUndecidableHosts() {
        // Java URI 层即拒绝（语法非法 / IPv4 校验失败 / 数字开头混合段 host 不可判定），默认不放行
        for (final String endpoint : new String[] {"http://exa mple.com/x", "http://sub.localhost/x",
                "http://0.0.0.0/x", "http://1.2.3.4.5/x", "http://999.1.2.3/x", "http://172.abc.0.1/x"}) {
            assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.GOV_EXCHANGE, endpoint, false))
                    .as(endpoint)
                    .isInstanceOf(ConnectorBizException.class);
        }
    }

    @Test
    void redactFallsBackOnUnparseableOrSchemelessInput() {
        assertThat(SourceEndpointPolicy.redact("http://exa mple.com/x")).isEqualTo("<redacted>");
        assertThat(SourceEndpointPolicy.redact("/path/only")).isEqualTo("<redacted>");
    }

    /**
     * 放行对照腿逐段 1 例（WBS-3.6.8 W7 / DB-93，hifi §3 T8）：受限判定集的每个保留段
     * （0.0.0.0/8、100.64/10、192.0.0/24、192.0.2/24、198.18/15、198.51.100/24、203.0.113/24、240/4）
     * 与受限判定共用短路分支——默认模式逐段拒绝、显式放开（{@code allow-private-endpoint=true}）
     * 逐段放行，双向同测防"只放开机制不放开段"或"逐段判定集漂移"。
     */
    @Test
    void permissiveSwitchAdmitsEachRestrictedSegmentAsCounterLeg() {
        final String[] restrictedSegments = {"0.0.0.1", "100.64.0.1", "192.0.0.1", "192.0.2.1",
                "198.18.0.1", "198.51.100.1", "203.0.113.1", "240.0.0.1"};
        for (final String ip : restrictedSegments) {
            assertThat(SourceEndpointPolicy.isPrivateHost(ip))
                    .as("保留段 " + ip + " 属受限判定集（对照前提）").isTrue();
            final String endpoint = "http://" + ip + "/dataset";
            assertThatThrownBy(() -> SourceEndpointPolicy.normalize(SourceType.API_GATEWAY,
                    endpoint, false))
                    .as("默认模式逐段拒绝：" + ip)
                    .isInstanceOf(ConnectorBizException.class);
            assertThat(SourceEndpointPolicy.normalize(SourceType.API_GATEWAY, endpoint, true))
                    .as("显式放开逐段放行：" + ip)
                    .isEqualTo(endpoint);
        }
    }
}
