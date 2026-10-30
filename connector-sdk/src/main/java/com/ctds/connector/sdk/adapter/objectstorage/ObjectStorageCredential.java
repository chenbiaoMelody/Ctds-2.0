package com.ctds.connector.sdk.adapter.objectstorage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * 对象存储凭据形态（hifi §7.4）：提供方生成的<b>完整预签名 URL</b>（{@code http(s)://}，长度 ≤1024）。
 *
 * <p>适配器只发 GET、不做签名实现（红线：不自实现密码学）；{@link #toString()} 不输出 URL 任何片段
 * （末段即签名片段，不进日志 / 异常）。</p>
 */
public record ObjectStorageCredential(String url) {

    /** 预签名 URL 长度上限（与 hifi §2 凭据明文上限一致）。 */
    public static final int MAX_LENGTH = 1024;

    private static final String MASKED = "***";
    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    /** 解析凭据明文（形态非法返回空）。 */
    public static Optional<ObjectStorageCredential> parse(final String plaintext) {
        if (plaintext == null) {
            return Optional.empty();
        }
        final String trimmed = plaintext.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            final URI uri = new URI(trimmed);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
                return Optional.empty();
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(new ObjectStorageCredential(trimmed));
        } catch (final URISyntaxException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "ObjectStorageCredential[url=" + MASKED + "]";
    }
}
