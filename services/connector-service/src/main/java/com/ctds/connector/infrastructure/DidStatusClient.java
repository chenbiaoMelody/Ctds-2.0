package com.ctds.connector.infrastructure;

import com.ctds.connector.config.ConnectorProperties;
import com.ctds.connector.domain.CurrentDid;
import com.ctds.connector.domain.DidStatusPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * 主体当前 DID 只读客户端（hifi §8-②：DID 域新增内部只读端点）。
 * 服务身份头 connector-service / connector-internal（权限点 did.internal.read，ADR-017 补记）。
 * 未配置 / 不可达 / 超时 / 非 200 / 不可解析 → UNAVAILABLE（调用方 fail-closed 503）。
 */
@Component
public class DidStatusClient implements DidStatusPort {

    private static final Logger log = LoggerFactory.getLogger(DidStatusClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTERNAL_SUBJECT = "connector-service";
    private static final String INTERNAL_ROLES = "connector-internal";

    private final String baseUrl;
    private final Duration readTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DidStatusClient(final ConnectorProperties properties, final ObjectMapper objectMapper) {
        this.baseUrl = properties.getDid().getBaseUrl();
        this.readTimeout = properties.getDid().getTimeout();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public CurrentDid current(final String subjectNo) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.connector.did.base-url 未配置，DID 判定不可用: subjectNo={}", subjectNo);
            return CurrentDid.unavailable();
        }
        final HttpResponse<String> response;
        try {
            final String path = UriUtils.encodePathSegment(subjectNo, StandardCharsets.UTF_8);
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/did/internal/subjects/" + path + "/current"))
                    .timeout(readTimeout)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            log.warn("DID 服务不可达，DID 判定不可用: subjectNo={}", subjectNo, e);
            return CurrentDid.unavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DID 服务调用被中断，DID 判定不可用: subjectNo={}", subjectNo);
            return CurrentDid.unavailable();
        } catch (final RuntimeException e) {
            log.warn("DID 服务请求构造失败，DID 判定不可用: subjectNo={}", subjectNo, e);
            return CurrentDid.unavailable();
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("DID 服务响应解析失败: subjectNo={}, status={}", subjectNo, response.statusCode(), e);
            return CurrentDid.unavailable();
        }
        if (response.statusCode() != 200 || !"0".equals(body.path("code").asText())) {
            log.warn("DID 服务响应非正常: subjectNo={}, status={}, code={}", subjectNo,
                    response.statusCode(), body.path("code").asText());
            return CurrentDid.unavailable();
        }
        final JsonNode data = body.path("data");
        final String status = data.path("status").asText();
        if (!CurrentDid.ACTIVE.equals(status) && !CurrentDid.REVOKED.equals(status)
                && !CurrentDid.PENDING_ISSUE.equals(status) && !CurrentDid.NONE.equals(status)) {
            log.warn("DID 服务返回未知状态，按不可判定处理: subjectNo={}, status={}", subjectNo, status);
            return CurrentDid.unavailable();
        }
        final String did = data.path("did").isNull() ? null : data.path("did").asText(null);
        return new CurrentDid(did, status);
    }
}
