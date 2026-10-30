package com.ctds.connector.sdk.adapter.apigateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * API 网关只读客户端（hifi §7.5）：JDK {@link HttpClient}，GET，{@code Authorization} 原样放入请求头。
 *
 * <p>读取模式最多消费 1MB 响应体<b>仅用于计字节数</b>，随后丢弃（不落内存、不入摘要、不回传）。
 * 超时由装配注入（{@code ctds.connector.source.adapter-timeout}），不在此硬编码（评审循环 1 ③P1）。</p>
 */
final class ApiGatewayClient {

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private final Duration timeout;
    private final HttpClient client;

    ApiGatewayClient(final Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 只读探测。
     *
     * @param endpoint      源地址（http(s)）
     * @param authorization 完整 Authorization 头值（不解析 / 不拼接 / 不改写）
     * @param countBody     true = 读取至多 1MB 计字节数；false = 只取响应头
     */
    HttpProbeResult probe(final String endpoint, final String authorization, final boolean countBody)
            throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", authorization)
                .GET()
                .build();
        try {
            final HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long bytes = 0;
            try (InputStream body = response.body()) {
                if (countBody) {
                    bytes = count(body);
                }
            }
            final String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new HttpProbeResult(response.statusCode(), bytes, contentType);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("API 网关探测被中断", e);
        }
    }

    /** 统计响应体字节数（至多 1MB，随后丢弃）。 */
    private static long count(final InputStream body) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while (total < MAX_BODY_BYTES) {
            final int limit = (int) Math.min(buffer.length, MAX_BODY_BYTES - total);
            read = body.read(buffer, 0, limit);
            if (read <= 0) {
                break;
            }
            total += read;
        }
        return total;
    }
}
