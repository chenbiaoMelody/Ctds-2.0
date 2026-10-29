package com.ctds.connector.sdk.adapter.objectstorage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 预签名 URL 只读客户端（hifi §7.4）：JDK {@link HttpClient}，GET；自检带 {@code Range: bytes=0-0}
 * （兼容只签 GET 的预签名），读取不设 Range 且只取响应头，<b>响应内容不落内存</b>。
 *
 * <p>仅 4xx / 5xx 时读限长片段用于签名类关键字判定（大小写不敏感），成功应答直接关闭流。
 * 超时由装配注入（{@code ctds.connector.source.adapter-timeout}），不在此硬编码（评审循环 1 ③P1）。</p>
 */
final class PresignedUrlClient {

    private static final int ERROR_BODY_MAX = 2048;

    private final Duration timeout;
    private final HttpClient client;

    PresignedUrlClient(final Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 只读探测。
     *
     * @param url       预签名 URL
     * @param rangeOnly 是否带 {@code Range: bytes=0-0}（自检用）
     */
    Probe probe(final String url, final boolean rangeOnly) throws IOException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET();
        if (rangeOnly) {
            builder.header("Range", "bytes=0-0");
        }
        try {
            final HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            final int status = response.statusCode();
            final long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            final String contentType = response.headers().firstValue("Content-Type").orElse("");
            final String snippet;
            try (InputStream body = response.body()) {
                snippet = status >= 400 ? readLimited(body) : "";
            }
            return new Probe(status, contentLength, contentType, snippet);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("对象存储探测被中断", e);
        }
    }

    private static String readLimited(final InputStream body) throws IOException {
        final byte[] buffer = new byte[ERROR_BODY_MAX];
        int total = 0;
        int read;
        while (total < ERROR_BODY_MAX && (read = body.read(buffer, total, ERROR_BODY_MAX - total)) > 0) {
            total += read;
        }
        return new String(buffer, 0, total, StandardCharsets.UTF_8);
    }

    /** 探测结果（status / 响应头两个字段 / 失败片段；不含对象内容）。 */
    record Probe(int status, long contentLength, String contentType, String bodySnippet) {
    }
}
