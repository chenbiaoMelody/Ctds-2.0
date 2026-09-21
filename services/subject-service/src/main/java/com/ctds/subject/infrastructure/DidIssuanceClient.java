package com.ctds.subject.infrastructure;

import com.ctds.subject.domain.DidIssuancePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * DID 签发触发客户端（JDK HttpClient，沿 KmsKeyProvider 先例，零新增依赖）。
 * 未配置 base-url = 跳过（服务可独立启动）；配置后调用失败抛异常，由 DidIssuanceTrigger 收敛为 WARN。
 */
@Component
public class DidIssuanceClient implements DidIssuancePort {

    private static final Logger log = LoggerFactory.getLogger(DidIssuanceClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DidIssuanceClient(@Value("${ctds.did.issuance.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public void triggerIssuance(final String subjectNo, final String subjectName, final String subjectType) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.did.issuance.base-url 未配置，跳过 DID 签发触发: subjectNo={}", subjectNo);
            return;
        }
        final HttpResponse<String> response;
        try {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("subjectNo", subjectNo);
            body.put("subjectName", subjectName);
            body.put("subjectType", subjectType);
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/did/issuances"))
                    .timeout(READ_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("DID 签发请求序列化失败", e);
        } catch (final IOException e) {
            throw new IllegalStateException("DID 服务不可达", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DID 签发调用被中断", e);
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("DID 签发响应非 200: status=" + response.statusCode());
        }
        final JsonNode json;
        try {
            json = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("DID 签发响应解析失败", e);
        }
        final String code = json.path("code").asText();
        if (!"0".equals(code)) {
            throw new IllegalStateException("DID 签发触发未成功: code=" + code);
        }
    }
}
