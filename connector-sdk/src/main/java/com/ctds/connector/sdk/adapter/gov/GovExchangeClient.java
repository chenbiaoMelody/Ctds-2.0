package com.ctds.connector.sdk.adapter.gov;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 政务交换 HTTP 客户端（hifi §7-⑤）：{@code http(s)://} 走真实 HTTP，连接异常 / 超时
 * 由调用方统一映射为"网络不可达"；401 / 403 映射三类业务原因；其它状态 = 非预期（上抛，平台侧 502）。
 *
 * <p>V1.0 未与真实政务平台联调，报文协议为本地约定（ADR-029 登记）；超时来自
 * {@code ctds.connector.source.adapter-timeout}。零新第三方依赖（Spring Web 既有）。</p>
 */
final class GovExchangeClient {

    private final RestClient restClient;

    GovExchangeClient(final Duration timeout) {
        // JDK HttpClient（java.net.http，JDK 17 内置）：5xx / 3xx 应答可被读到状态码，
        // 不会像 HttpURLConnection 那样以 IOException 形式提前中断（否则 401 / 403 / 其它状态分支不可达）；
        // 重定向默认不跟随（Redirect.NEVER），3xx 一律按非预期应答上抛。
        final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 发送标准请求报文（应答体由容器关闭：{@code exchange(..., true)}，避免 401 / 403 早退时连接泄漏）。
     *
     * @throws ResourceAccessException 连接失败 / 超时（网络不可达）
     * @throws IllegalStateException   非预期应答（3xx 重定向 / 其它非 2xx / 应答不可解析）；
     *                                 POST 不经 HttpURLConnection 自动跟随重定向，重定向一律视为异常
     */
    GovExchangeMessage exchange(final GovExchangeMessage request, final String credential) {
        return restClient.post()
                .uri(request.endpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((outbound, response) -> {
                    final int status = response.getStatusCode().value();
                    if (status == 401) {
                        return GovExchangeMessage.failure(GovExchangeMessage.INVALID_CREDENTIAL, "凭据无效");
                    }
                    if (status == 403) {
                        return GovExchangeMessage.failure(
                                GovExchangeMessage.INSUFFICIENT_PERMISSION, "权限不足");
                    }
                    if (status < 200 || status >= 300) {
                        throw new IllegalStateException("政务交换应答状态异常（status=" + status + "）");
                    }
                    final GovExchangeMessage body = response.bodyTo(GovExchangeMessage.class);
                    if (body == null) {
                        throw new IllegalStateException("政务交换应答为空");
                    }
                    return body;
                }, true);
    }
}
