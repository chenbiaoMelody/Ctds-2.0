package com.ctds.did.infrastructure;

import com.ctds.did.domain.DidKmsClient;
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
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * DID → KMS 密钥对托管与内部签名客户端（JDK HttpClient，沿 KmsKeyProvider 先例，零新增依赖）。
 * 内部回环诚实边界（ADR-016 §2.7）：以服务身份头（did-service / admin 角色 → kms.admin）调用，
 * 网络隔离是真实边界；失败抛异常由应用层收敛（签发侧 PENDING_ISSUE、演示签名侧 1005S0002）。
 * 私钥零明文：本客户端只调用生成/签名面，**不接触任何密钥材料读取面**（ADR-017 §2.8）。
 */
@Component
public class DidKmsHttpClient implements DidKmsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "did-service";
    private static final String INTERNAL_ROLES = "admin";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DidKmsHttpClient(@Value("${ctds.did.kms.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public String createKeyPair(final String keyRef) {
        return post("/api/v1/key-pairs", Map.of("keyRef", keyRef)).path("publicKeyHex").asText();
    }

    @Override
    public String sign(final String keyRef, final String dataBase64) {
        return post("/api/v1/key-pairs/" + keyRef + "/signatures", Map.of("data", dataBase64))
                .path("signature").asText();
    }

    /** 统一回环 POST（JSON）：返回 ApiResult.data 节点；非 200 / 非 0 业务码一律抛异常。 */
    private JsonNode post(final String path, final Map<String, String> body) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("ctds.did.kms.base-url 未配置");
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(READ_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("KMS 请求序列化失败", e);
        } catch (final IOException e) {
            throw new IllegalStateException("KMS 不可达", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KMS 调用被中断", e);
        }
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("KMS 响应非 200: status=" + response.statusCode());
        }
        final JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("KMS 响应解析失败", e);
        }
        final String code = envelope.path("code").asText();
        if (!"0".equals(code)) {
            throw new IllegalStateException("KMS 调用失败: code=" + code);
        }
        return envelope.path("data");
    }
}
