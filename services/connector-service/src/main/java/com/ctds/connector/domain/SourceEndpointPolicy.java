package com.ctds.connector.domain;

import com.ctds.connector.sdk.spi.SourceType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 源地址受理规则（hifi §7-⑧，评审循环 1 安全加固）：按源类型的 scheme 白名单、禁止 URL 内嵌凭据、
 * 默认拒绝 HTTP(S) 回环 / 内网 / 链路本地地址（防 SSRF），审计留痕侧脱敏。
 *
 * <p>凭据只能经 {@code credential} 字段提交并落国密密文；地址里出现 userinfo
 * （{@code https://user:pass@host} / {@code jdbc:mysql://user:pass@host}）一律拒绝，避免凭据被当作地址
 * 入库、进留痕、回显响应；查询串凭据样键（{@code ?password=} 等）与 userinfo 同构，一并拒绝。
 * 内网地址默认拒绝（{@code ctds.connector.source.allow-private-endpoint=false}），且对字面量
 * 编码形态（IPv4-mapped IPv6、前导零点分十进制、1~4 段整数变体）先规范化再判定。</p>
 *
 * <p>非 HTTP(S) scheme（{@code jdbc:} / {@code oss:} / {@code s3:}）本卡不发起出站调用，主机策略由 3.6.6
 * 对应适配器在其调用边界实施；本卡只做 scheme 归属与 URL 内嵌凭据拒绝。校验层不做 DNS 解析
 * （避免校验期联网与 TOCTOU），主机名解析到内网的残余由 3.6.6 调用边界实施解析级校验。</p>
 */
public final class SourceEndpointPolicy {

    /** 源地址长度上限（与 hifi §4 请求约束一致）。 */
    public static final int ENDPOINT_MAX = 512;

    static final String GOV_MOCK_PREFIX = "mock://gov-exchange/";
    private static final String REDACTED = "<redacted>";
    private static final int IPV4_MAX = 255;
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final Set<String> HTTP_SCHEMES = Set.of(HTTP, HTTPS);

    /** 四类源的 scheme 归属（DATABASE = JDBC 原生地址；OBJECT_STORAGE = 对象存储原生 scheme）。 */
    private static final Map<SourceType, Set<String>> TYPE_SCHEMES = Map.of(
            SourceType.GOV_EXCHANGE, Set.of("mock", HTTP, HTTPS),
            SourceType.DATABASE, Set.of(HTTP, HTTPS, "jdbc"),
            SourceType.OBJECT_STORAGE, Set.of(HTTP, HTTPS, "oss", "s3"),
            SourceType.API_GATEWAY, HTTP_SCHEMES);

    /** 查询串凭据样键黑名单（完整键、大小写不敏感；命中即拒绝——与 userinfo 同构的明文旁路）。 */
    private static final Set<String> CREDENTIAL_QUERY_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "credentials",
            "accesskey", "access-key", "access_key", "secretkey", "secret-key", "secret_key",
            "apikey", "api-key", "api_key", "privatekey", "private-key", "private_key",
            "passphrase", "authorization", "auth");

    private static final String MAPPED_V6_MARKER = ":ffff:";

    private SourceEndpointPolicy() {
    }

    /**
     * 规范化并校验源地址。
     *
     * @param type             源类型（{@code mock://} 仅政务源可用）
     * @param endpoint         用户提交的源地址
     * @param allowPrivateHost 是否允许回环 / 内网地址（仅联调与测试放开）
     * @return 去首尾空白后的地址
     */
    public static String normalize(final SourceType type, final String endpoint,
            final boolean allowPrivateHost) {
        if (endpoint == null) {
            throw invalid();
        }
        final String trimmed = endpoint.trim();
        if (trimmed.isEmpty() || trimmed.length() > ENDPOINT_MAX || hasBlankOrControl(trimmed)) {
            throw invalid();
        }
        final URI uri = parse(trimmed);
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        final Set<String> allowed = TYPE_SCHEMES.getOrDefault(type, HTTP_SCHEMES);
        if (!allowed.contains(scheme) || hasEmbeddedUserInfo(uri)
                || hasCredentialLikeQueryKey(trimmed)) {
            throw invalid();
        }
        if ("mock".equals(scheme)) {
            if (type != SourceType.GOV_EXCHANGE || !trimmed.startsWith(GOV_MOCK_PREFIX)) {
                throw invalid();
            }
            return trimmed;
        }
        if (!HTTP_SCHEMES.contains(scheme)) {
            return trimmed;
        }
        final String host = uri.getHost();
        if (host == null || host.isBlank() || (!allowPrivateHost && isPrivateHost(host))) {
            throw invalid();
        }
        return trimmed;
    }

    /** 审计 / 日志用地址形态：去掉查询串与片段（URL 里的 token / password 不进入留痕）。 */
    public static String redact(final String endpoint) {
        if (endpoint == null) {
            return REDACTED;
        }
        try {
            final URI uri = new URI(endpoint.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return REDACTED;
            }
            final String rawAuthority = uri.getRawAuthority();
            final int at = rawAuthority.lastIndexOf('@');
            final String authority = (at < 0 ? rawAuthority : rawAuthority.substring(at + 1))
                    .replace(";", "%3B");
            final String path = uri.getRawPath() == null ? "" : uri.getRawPath().replace(";", "%3B");
            return uri.getScheme() + "://" + authority + path;
        } catch (final URISyntaxException e) {
            return REDACTED;
        }
    }

    private static URI parse(final String value) {
        try {
            return new URI(value);
        } catch (final URISyntaxException e) {
            throw invalid();
        }
    }

    /** URL 内嵌凭据：分层 URI 看 userInfo，不透明 URI（jdbc: 等）在 authority 段找 {@code @}。 */
    private static boolean hasEmbeddedUserInfo(final URI uri) {
        if (uri.getUserInfo() != null) {
            return true;
        }
        final String part = uri.getSchemeSpecificPart();
        if (part == null) {
            return false;
        }
        final int authority = part.indexOf("://");
        if (authority < 0) {
            return false;
        }
        final int at = part.indexOf('@', authority);
        if (at < 0) {
            return false;
        }
        final int path = part.indexOf('/', authority + 3);
        return path < 0 || at < path;
    }

    /** 查询串凭据样键：{@code ?password=} 等命中即拒绝（{@code mock:} 等任何 scheme 一致适用）。 */
    private static boolean hasCredentialLikeQueryKey(final String endpoint) {
        final int query = endpoint.indexOf('?');
        if (query < 0) {
            return false;
        }
        final int fragment = endpoint.indexOf('#', query);
        final String pairs = fragment < 0 ? endpoint.substring(query + 1)
                : endpoint.substring(query + 1, fragment);
        for (final String pair : pairs.split("&")) {
            final int equals = pair.indexOf('=');
            final String key = (equals < 0 ? pair : pair.substring(0, equals))
                    .toLowerCase(Locale.ROOT);
            if (CREDENTIAL_QUERY_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBlankOrControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                return true;
            }
        }
        return false;
    }

    /** 回环 / 内网 / 链路本地 / 云元数据地址判定（CWE-918 常见目标），先做字面量规范化再比对。 */
    static boolean isPrivateHost(final String host) {
        final String normalized = stripBrackets(host.toLowerCase(Locale.ROOT));
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("::1") || normalized.equals("0.0.0.0")) {
            return true;
        }
        if (normalized.startsWith("127.") || normalized.startsWith("10.")
                || normalized.startsWith("192.168.") || normalized.startsWith("169.254.")) {
            return true;
        }
        if (isPrivate172(normalized)) {
            return true;
        }
        if (normalized.contains(":")) {
            if (normalized.startsWith("fc") || normalized.startsWith("fd")
                    || normalized.startsWith("fe80")) {
                return true;
            }
            return isMappedIPv6Private(normalized);
        }
        return isNumericV4Host(normalized);
    }

    /** IPv4-mapped IPv6 字面量（{@code ::ffff:127.0.0.1} 点分与 {@code ::ffff:7f00:1} 十六进制两态）。 */
    private static boolean isMappedIPv6Private(final String host) {
        final int marker = host.indexOf(MAPPED_V6_MARKER);
        if (marker < 0) {
            return false;
        }
        final String tail = host.substring(marker + MAPPED_V6_MARKER.length());
        if (tail.indexOf('.') >= 0) {
            final String[] parts = tail.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            return withV4Bytes(parseDecimal(parts[0]), parseDecimal(parts[1]),
                    parseDecimal(parts[2]), parseDecimal(parts[3]));
        }
        final String[] parts = tail.split(":");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return false;
        }
        try {
            final long high = Long.parseLong(parts[0], 16);
            final long low = Long.parseLong(parts[1], 16);
            return withV4Bytes(high >> 8, high & 0xFF, low >> 8, low & 0xFF);
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private static boolean isPrivate172(final String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        final String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            final int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /**
     * 十进制 / 十六进制整数形态与点分十进制主机名。前导零段按 JDK {@code InetAddress} 语义作十进制
     * （{@code 010.1.2.3} = 10.1.2.3）；2~3 段按 inet_aton 变体展开（{@code 127.1} = 127.0.0.1）；
     * 段值超过 255 视为非法 v4 形态放行（JDK 解析失败，连接期按"网络不可达"兜底）。
     */
    private static boolean isNumericV4Host(final String host) {
        if (host.matches("\\d+")) {
            return true;
        }
        if (host.startsWith("0x") && host.matches("0x[0-9a-f]+")) {
            return true;
        }
        final String[] parts = host.split("\\.");
        if (parts.length < 2 || parts.length > 4) {
            return false;
        }
        for (final String part : parts) {
            if (!part.matches("\\d+")) {
                return false;
            }
        }
        final long[] value = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            value[index] = parseDecimal(parts[index]);
            if (value[index] < 0 || value[index] > IPV4_MAX) {
                return false;
            }
        }
        if (parts.length == 4) {
            return withV4Bytes(value[0], value[1], value[2], value[3]);
        }
        if (parts.length == 3) {
            return withV4Bytes(value[0], value[1], value[2] >> 8, value[2] & 0xFF);
        }
        return withV4Bytes(value[0], value[1] >> 16, (value[1] >> 8) & 0xFF, value[1] & 0xFF);
    }

    /** 前导零段按十进制解析（JDK 语义）；非数字段返回 -1 由调用方放行。 */
    private static long parseDecimal(final String part) {
        try {
            return Long.parseLong(part);
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    /** IPv4 四元组的私有 / 回环 / 链路本地判定（含 {@code 0.0.0.0}）；段越界返回 false。 */
    private static boolean withV4Bytes(final long a, final long b, final long c, final long d) {
        if (a < 0 || b < 0 || c < 0 || d < 0
                || a > IPV4_MAX || b > IPV4_MAX || c > IPV4_MAX || d > IPV4_MAX) {
            return false;
        }
        return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 169 && b == 254)
                || (a == 0 && b == 0 && c == 0 && d == 0);
    }

    private static String stripBrackets(final String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static ConnectorBizException invalid() {
        return new ConnectorBizException(ConnectorErrorCodes.PARAM_INVALID,
                ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
    }
}
