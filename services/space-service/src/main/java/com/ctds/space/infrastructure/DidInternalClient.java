package com.ctds.space.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空间 → DID 服务 主体当前 DID 只读客户端（WBS-3.8.3 hifi §3.1 / §5，Q6-A）。
 * 服务身份头 space-service / space-internal（did 服务新增只读映射 did.internal.read）；
 * 只读 subjectNo / did / status 三字段，不落库、不缓存；日志不打印 DID。
 * 未配置 / 不可达 / 非 ACTIVE / 无记录 → 空（调用方跳过该条存证，不伪造主体标识）。
 */
@Component
public class DidInternalClient {

    private static final Logger log = LoggerFactory.getLogger(DidInternalClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "space-service";
    private static final String INTERNAL_ROLES = "space-internal";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String DID_PREFIX = "did:ctds:";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DidInternalClient(@Value("${ctds.space.did.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 主体当前 ACTIVE DID（取不到时为空，调用方跳过存证并告警）。 */
    public Optional<String> activeDid(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank()) {
            return Optional.empty();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.space.did.base-url 未配置，空间存证主体 DID 不可用: subjectNo={}", subjectNo);
            return Optional.empty();
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/did/internal/subjects/" + subjectNo + "/current"))
                    .timeout(READ_TIMEOUT)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (final IOException e) {
            log.warn("DID 服务不可达，空间存证跳过: subjectNo={}", subjectNo);
            return Optional.empty();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DID 服务调用被中断，空间存证跳过: subjectNo={}", subjectNo);
            return Optional.empty();
        } catch (final RuntimeException e) {
            log.warn("DID 服务请求构造失败，空间存证跳过: subjectNo={}", subjectNo);
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            log.warn("DID 服务响应非正常，空间存证跳过: subjectNo={}, status={}", subjectNo,
                    response.statusCode());
            return Optional.empty();
        }
        final JsonNode data;
        try {
            final JsonNode body = objectMapper.readTree(response.body());
            if (body == null || !"0".equals(body.path("code").asText())) {
                log.warn("DID 服务响应非正常，空间存证跳过: subjectNo={}", subjectNo);
                return Optional.empty();
            }
            data = body.path("data");
        } catch (final IOException e) {
            log.warn("DID 服务响应解析失败，空间存证跳过: subjectNo={}", subjectNo);
            return Optional.empty();
        }
        final String status = data.path("status").asText();
        if (!STATUS_ACTIVE.equals(status)) {
            log.warn("主体 DID 非 ACTIVE，空间存证跳过: subjectNo={}, status={}", subjectNo, status);
            return Optional.empty();
        }
        final String did = data.path("did").asText("");
        if (!did.startsWith(DID_PREFIX)) {
            log.warn("主体 DID 缺失或值域非法，空间存证跳过: subjectNo={}", subjectNo);
            return Optional.empty();
        }
        return Optional.of(did);
    }
}
