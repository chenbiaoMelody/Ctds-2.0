package com.ctds.connector.infrastructure;

import com.ctds.connector.domain.ConnectorBizException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.SourceHostGuard;
import com.ctds.connector.sdk.adapter.database.JdbcUrlPolicy;
import com.ctds.connector.sdk.spi.SourceType;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 解析级主机防护实现（hifi §4；DB-91）：{@link InetAddress#getAllByName} 全量判定——
 * 回环 / 任意本地 / 内网 / 链路本地 / 组播 / IPv6 唯一本地地址，或解析失败，一律拒绝。
 *
 * <p>{@code ctds.connector.source.allow-private-endpoint=true} 仅用于测试 / 演示显式放开受限网段
 * （<b>解析失败仍拒绝</b>，因为无法证明目标安全）；默认 false = 拒绝。拒绝是业务异常
 * {@code 1010C0010}，不当成系统错误。</p>
 */
public class InetAddressSourceHostGuard implements SourceHostGuard {

    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_MAPPED_LENGTH = 16;
    private static final int OCTET_MASK = 0xFF;
    private static final int UNIQUE_LOCAL_PREFIX_MASK = 0xFE;
    private static final int UNIQUE_LOCAL_PREFIX = 0xFC;
    private static final int CG_NAT_FIRST = 100;
    private static final int CG_NAT_SECOND_MIN = 64;
    private static final int CG_NAT_SECOND_MAX = 127;
    private static final int BENCHMARK_FIRST = 198;
    private static final int BENCHMARK_SECOND_MIN = 18;
    private static final int BENCHMARK_SECOND_MAX = 19;
    private static final int RESERVED_FIRST_MIN = 240;
    private static final int NAT64_PREFIX_SECOND = 0x64;
    private static final int NAT64_PREFIX_THIRD = 0xFF;
    private static final int NAT64_PREFIX_FOURTH = 0x9B;
    private static final int SIX_TO_FOUR_FIRST = 0x20;
    private static final int SIX_TO_FOUR_SECOND = 0x02;
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String MOCK_SCHEME = "mock:";

    private final boolean allowPrivateEndpoint;

    public InetAddressSourceHostGuard(final boolean allowPrivateEndpoint) {
        this.allowPrivateEndpoint = allowPrivateEndpoint;
    }

    @Override
    public void assertAllowed(final SourceType type, final String endpoint,
            final String credentialPlain) {
        for (final String host : hostsOf(type, endpoint, credentialPlain)) {
            verify(host);
        }
    }

    /**
     * 按源类型提取需要判定的主机（对象存储同时校验凭据中的预签名 URL 主机）。
     *
     * <p><b>fail-closed</b>（评审循环 1 ②P2）：无法提取主机 = 无法证明目标安全 → 直接拒绝；
     * 仅对象存储允许"源地址为 {@code s3://} / {@code oss://} 且凭据为空"的无出站形态。</p>
     */
    private static Set<String> hostsOf(final SourceType type, final String endpoint,
            final String credentialPlain) {
        final Set<String> hosts = new LinkedHashSet<>();
        if (type == SourceType.DATABASE) {
            final Optional<String> host = JdbcUrlPolicy.parse(endpoint)
                    .map(JdbcUrlPolicy.Target::host);
            if (host.isEmpty()) {
                throw notAllowed();
            }
            hosts.add(host.get());
            return hosts;
        }
        if (type == SourceType.OBJECT_STORAGE) {
            if (credentialPlain != null && !credentialPlain.isBlank()) {
                final Optional<String> credentialHost = httpHost(credentialPlain);
                if (credentialHost.isEmpty()) {
                    throw notAllowed();
                }
                hosts.add(credentialHost.get());
            }
            httpHost(endpoint).ifPresent(hosts::add);
            return hosts;
        }
        if (isNonNetworkScheme(endpoint)) {
            return hosts;
        }
        final Optional<String> host = httpHost(endpoint);
        if (host.isEmpty()) {
            throw notAllowed();
        }
        hosts.add(host.get());
        return hosts;
    }

    /** 显式无出站 scheme 白名单（模拟源 {@code mock:}）：不出网即无触达面，其余一律要求可提取主机。 */
    private static boolean isNonNetworkScheme(final String endpoint) {
        return endpoint != null && endpoint.startsWith(MOCK_SCHEME);
    }

    private static Optional<String> httpHost(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            final URI uri = new URI(value.trim());
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
                return Optional.empty();
            }
            final String host = uri.getHost();
            return host == null || host.isBlank() ? Optional.empty() : Optional.of(host);
        } catch (final URISyntaxException e) {
            return Optional.empty();
        }
    }

    private void verify(final String host) {
        final String normalized = stripBrackets(host);
        if (normalized.isEmpty()) {
            throw notAllowed();
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (final UnknownHostException e) {
            throw notAllowed();
        }
        if (addresses.length == 0) {
            throw notAllowed();
        }
        if (allowPrivateEndpoint) {
            return;
        }
        for (final InetAddress address : addresses) {
            if (isRestricted(address)) {
                throw notAllowed();
            }
        }
    }

    private static boolean isRestricted(final InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalV6(address)
                || isReservedOrEmbeddedPrivate(address);
    }

    /** IPv6 唯一本地地址（{@code fc00::/7}；JDK {@code isSiteLocalAddress} 只覆盖已废弃的 fec0::/10）。 */
    private static boolean isUniqueLocalV6(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        return bytes.length == IPV6_MAPPED_LENGTH
                && (bytes[0] & UNIQUE_LOCAL_PREFIX_MASK) == UNIQUE_LOCAL_PREFIX;
    }

    /**
     * 保留 / 特殊用途网段与 IPv6 内嵌 IPv4 的私网（评审循环 1 ②P1）：JDK 谓词只覆盖回环 / 链路本地 /
     * 站点本地，{@code 100.64.0.0/10}（CGNAT，含云元数据 100.100.100.200）、{@code 192.0.0.0/24}、
     * {@code 0.0.0.0/8}、{@code 198.18.0.0/15}、{@code 240.0.0.0/4} 等需自行判定；
     * NAT64（{@code 64:ff9b::/96}）与 6to4（{@code 2002::/16}）需按内嵌 IPv4 再判一次。
     */
    private static boolean isReservedOrEmbeddedPrivate(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        if (bytes.length == IPV4_LENGTH) {
            return isReservedV4(bytes[0] & OCTET_MASK, bytes[1] & OCTET_MASK, bytes[2] & OCTET_MASK);
        }
        if (bytes.length != IPV6_MAPPED_LENGTH) {
            return false;
        }
        if ((bytes[0] & OCTET_MASK) == SIX_TO_FOUR_FIRST
                && (bytes[1] & OCTET_MASK) == SIX_TO_FOUR_SECOND) {
            return isReservedV4(bytes[2] & OCTET_MASK, bytes[3] & OCTET_MASK, bytes[4] & OCTET_MASK);
        }
        if (isNat64(bytes)) {
            return isReservedV4(bytes[12] & OCTET_MASK, bytes[13] & OCTET_MASK, bytes[14] & OCTET_MASK);
        }
        return false;
    }

    /** 内嵌 IPv4 的完整受限判定（私网 / 回环 / 链路本地 / 保留段）。 */
    private static boolean isReservedV4(final int a, final int b, final int c) {
        return a == 0
                || a == 127
                || a == 10
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 169 && b == 254)
                || (a == CG_NAT_FIRST && b >= CG_NAT_SECOND_MIN && b <= CG_NAT_SECOND_MAX)
                || (a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == BENCHMARK_FIRST && b >= BENCHMARK_SECOND_MIN && b <= BENCHMARK_SECOND_MAX)
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || a >= RESERVED_FIRST_MIN;
    }

    /** NAT64 {@code 64:ff9b::/96}（前 12 字节固定，末 4 字节为内嵌 IPv4）。 */
    private static boolean isNat64(final byte[] bytes) {
        if (bytes[0] != 0
                || (bytes[1] & OCTET_MASK) != NAT64_PREFIX_SECOND
                || (bytes[2] & OCTET_MASK) != NAT64_PREFIX_THIRD
                || (bytes[3] & OCTET_MASK) != NAT64_PREFIX_FOURTH) {
            return false;
        }
        for (int index = 4; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripBrackets(final String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static ConnectorBizException notAllowed() {
        return new ConnectorBizException(ConnectorErrorCodes.SOURCE_ENDPOINT_NOT_ALLOWED,
                ConnectorErrorCodes.SOURCE_ENDPOINT_NOT_ALLOWED_MESSAGE);
    }
}
