package com.ctds.connector.sdk.adapter.database;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC URL 解析与参数白名单（WBS-3.6.6 hifi §7.3）：只接受 {@code jdbc:mysql://host[:port]/schema[?params]}，
 * 参数<b>白名单</b>（默认拒绝）——{@code allowLoadLocalInfile} / {@code autoDeserialize} /
 * {@code allowMultiQueries} 等危险参数及任何未知参数一律不可用。
 *
 * <p>纯函数、零外部依赖：不依赖 JDBC 驱动解析，也不做 DNS 解析（解析级主机校验归服务侧调用边界）。</p>
 */
public final class JdbcUrlPolicy {

    /** MySQL 默认端口。 */
    public static final int DEFAULT_PORT = 3306;

    private static final String PREFIX = "jdbc:mysql://";
    private static final int MAX_PORT = 65535;
    private static final String PARAMETER_SEPARATOR = "&";
    private static final String KEY_VALUE_SEPARATOR = "=";

    /** 允许的 URL 参数（小写；大小写不敏感比对）。 */
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "sslmode", "usessl", "requiressl", "characterencoding", "servertimezone",
            "connecttimeout", "sockettimeout", "allowpublickeyretrieval", "useunicode");

    private JdbcUrlPolicy() {
    }

    /**
     * 解析 JDBC URL。
     *
     * @param url 源地址（{@code jdbc:mysql://…}）
     * @return 合法时返回目标（host / port / schema / 原地址），非法返回空（调用方映射 400 或 502）
     */
    public static Optional<Target> parse(final String url) {
        if (url == null) {
            return Optional.empty();
        }
        final String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return Optional.empty();
        }
        final String remainder = trimmed.substring(PREFIX.length());
        final int queryIndex = remainder.indexOf('?');
        final String authorityAndSchema = queryIndex < 0 ? remainder : remainder.substring(0, queryIndex);
        final String query = queryIndex < 0 ? null : remainder.substring(queryIndex + 1);
        final int slash = authorityAndSchema.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        final String authority = authorityAndSchema.substring(0, slash);
        final String schema = authorityAndSchema.substring(slash + 1);
        if (schema.isEmpty() || schema.indexOf('/') >= 0 || authority.indexOf('@') >= 0) {
            return Optional.empty();
        }
        final HostAndPort hostAndPort = parseAuthority(authority);
        if (hostAndPort == null || !parametersAllowed(query)) {
            return Optional.empty();
        }
        return Optional.of(new Target(hostAndPort.host(), hostAndPort.port(), schema, trimmed));
    }

    private static HostAndPort parseAuthority(final String authority) {
        if (authority.isEmpty()) {
            return null;
        }
        if (authority.startsWith("[")) {
            final int end = authority.indexOf(']');
            if (end < 0) {
                return null;
            }
            final String host = authority.substring(1, end);
            final String after = authority.substring(end + 1);
            if (host.isEmpty()) {
                return null;
            }
            if (after.isEmpty()) {
                return new HostAndPort(host, DEFAULT_PORT);
            }
            if (!after.startsWith(":")) {
                return null;
            }
            final Integer port = parsePort(after.substring(1));
            return port == null ? null : new HostAndPort(host, port);
        }
        final int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            return authority.isEmpty() ? null : new HostAndPort(authority, DEFAULT_PORT);
        }
        final String host = authority.substring(0, colon);
        final Integer port = parsePort(authority.substring(colon + 1));
        if (host.isEmpty() || port == null) {
            return null;
        }
        return new HostAndPort(host, port);
    }

    private static Integer parsePort(final String value) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            final int port = Integer.parseInt(value);
            return port >= 1 && port <= MAX_PORT ? port : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static boolean parametersAllowed(final String query) {
        if (query == null) {
            return true;
        }
        for (final String pair : query.split(PARAMETER_SEPARATOR)) {
            if (pair.isEmpty()) {
                return false;
            }
            final int equals = pair.indexOf(KEY_VALUE_SEPARATOR);
            final String key = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !ALLOWED_PARAMETERS.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /** 解析结果（host 已去 IPv6 方括号；url 为去首尾空白后的原地址）。 */
    public record Target(String host, int port, String schema, String url) {
    }

    private record HostAndPort(String host, int port) {
    }
}
