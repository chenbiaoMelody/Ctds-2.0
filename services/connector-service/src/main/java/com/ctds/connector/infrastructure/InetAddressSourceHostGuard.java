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

    private static final int IPV6_MAPPED_LENGTH = 16;
    private static final int UNIQUE_LOCAL_PREFIX_MASK = 0xFE;
    private static final int UNIQUE_LOCAL_PREFIX = 0xFC;
    private static final String HTTP = "http";
    private static final String HTTPS = "https";

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

    /** 按源类型提取需要判定的主机（对象存储同时校验凭据中的预签名 URL 主机）。 */
    private static Set<String> hostsOf(final SourceType type, final String endpoint,
            final String credentialPlain) {
        final Set<String> hosts = new LinkedHashSet<>();
        if (type == SourceType.DATABASE) {
            JdbcUrlPolicy.parse(endpoint).map(JdbcUrlPolicy.Target::host)
                    .or(() -> httpHost(endpoint))
                    .ifPresent(hosts::add);
            return hosts;
        }
        httpHost(endpoint).ifPresent(hosts::add);
        if (type == SourceType.OBJECT_STORAGE) {
            httpHost(credentialPlain).ifPresent(hosts::add);
        }
        return hosts;
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
                || isUniqueLocalV6(address);
    }

    /** IPv6 唯一本地地址（{@code fc00::/7}；JDK {@code isSiteLocalAddress} 只覆盖已废弃的 fec0::/10）。 */
    private static boolean isUniqueLocalV6(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        return bytes.length == IPV6_MAPPED_LENGTH
                && (bytes[0] & UNIQUE_LOCAL_PREFIX_MASK) == UNIQUE_LOCAL_PREFIX;
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
