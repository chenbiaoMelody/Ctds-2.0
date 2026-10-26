package com.ctds.connector.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * 平台调用唯一通道（lofi §1-3 / hifi §4）：组装 Bearer 头、路径（pathSegment 编码）与载荷，
 * 三态归一——① 2xx 且 code="0" 且 data 非空返回 data；② 平台拒绝（非 2xx 或 code!="0"）
 * <b>原码原文透传</b>且不重试；③ IO / 超时 / 序列化转换失败按 {@link SdkErrorType} 中性分类
 * <b>fail-closed</b>——本类不存在任何"成功"旁路返回（规格行为 5 规则 2 / Q5-A）。
 *
 * <p>对外契约：门面方法只抛 {@link ConnectorException}（本类兜底捕获全部传输与转换异常）。</p>
 */
final class PlatformClient {

    /** 平台侧幂等头（ADR-007；文件型交付发起要求调用方提供）。 */
    static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    /** 网关交付链凭据头三件（ADR-022：合约编号 / 用途 / 域——判定输入）。 */
    static final String HEADER_CONTRACT_NO = "X-Ctds-Contract-No";

    static final String HEADER_PURPOSE = "X-Ctds-Purpose";

    static final String HEADER_TERRITORY = "X-Ctds-Territory";

    private final RestClient restClient;

    private final TokenProvider tokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    PlatformClient(RestClient.Builder builder, TokenProvider tokenProvider) {
        this.restClient = builder.build();
        this.tokenProvider = tokenProvider;
    }

    /** 生产构建入口（baseUrl 必填 + 超时配置）。 */
    static PlatformClient create(String baseUrl, TokenProvider tokenProvider,
            Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
        return new PlatformClient(builder, tokenProvider);
    }

    <T> T get(Function<UriBuilder, URI> uriFunction, Map<String, String> extraHeaders,
            ParameterizedTypeReference<PlatformResponse<T>> type) {
        try {
            PlatformResponse<T> body = restClient.get()
                    .uri(uriFunction)
                    .headers(headers -> mergeHeaders(headers, extraHeaders))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(type);
            return unwrap(body);
        } catch (RestClientResponseException e) {
            throw translateRejected(e);
        } catch (ResourceAccessException e) {
            throw translateUnreachable(e);
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ConnectorException(SdkErrorType.UNREACHABLE, "平台调用失败（请求或响应转换错误）", e);
        }
    }

    <T> T post(String path, Object payload, Map<String, String> extraHeaders,
            ParameterizedTypeReference<PlatformResponse<T>> type) {
        return post(uri -> uri.path(path).build(), payload, extraHeaders, type);
    }

    <T> T post(Function<UriBuilder, URI> uriFunction, Object payload, Map<String, String> extraHeaders,
            ParameterizedTypeReference<PlatformResponse<T>> type) {
        try {
            PlatformResponse<T> body = restClient.post()
                    .uri(uriFunction)
                    .headers(headers -> mergeHeaders(headers, extraHeaders))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(type);
            return unwrap(body);
        } catch (RestClientResponseException e) {
            throw translateRejected(e);
        } catch (ResourceAccessException e) {
            throw translateUnreachable(e);
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ConnectorException(SdkErrorType.UNREACHABLE, "平台调用失败（请求或响应转换错误）", e);
        }
    }

    private <T> T unwrap(PlatformResponse<T> body) {
        if (body == null || body.code() == null) {
            throw new ConnectorException(SdkErrorType.UNREACHABLE, "平台响应缺失统一结构（code 为空）", null);
        }
        if (!body.isSuccess()) {
            throw new ConnectorException(body.code(), body.message());
        }
        if (body.data() == null) {
            throw new ConnectorException(SdkErrorType.UNREACHABLE, "平台成功响应缺少数据（fail-closed）", null);
        }
        return body.data();
    }

    private ConnectorException translateRejected(RestClientResponseException e) {
        try {
            PlatformResponse<?> rejected = objectMapper
                    .readValue(e.getResponseBodyAsString(), new TypeReference<PlatformResponse<Object>>() {
                    });
            if (rejected != null && rejected.code() != null) {
                return new ConnectorException(rejected.code(), rejected.message());
            }
        } catch (IOException e2) {
            // 响应体非统一结构 JSON——落中性分类，不吞异常细节
        }
        return new ConnectorException(SdkErrorType.UNREACHABLE,
                "平台拒绝但响应体不可解析（HTTP " + e.getStatusCode().value() + "）", e);
    }

    private ConnectorException translateUnreachable(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new ConnectorException(SdkErrorType.TIMEOUT, "平台调用超时", e);
        }
        return new ConnectorException(SdkErrorType.UNREACHABLE, "平台不可达", e);
    }

    private void mergeHeaders(HttpHeaders headers, Map<String, String> extraHeaders) {
        String token = tokenProvider.token();
        if (token == null || token.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "Bearer 令牌为空（TokenProvider 未提供有效令牌）", null);
        }
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        extraHeaders.forEach(headers::set);
    }
}
