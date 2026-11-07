package com.ctds.evidence.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * 跨域 audit-trail 客户端公共段（WBS-3.8.4 hifi §4.4；沿 delivery ContractInternalClient 范式）：
 * JDK HttpClient（connect 2s / read 3s）、X-Ctds-Subject / X-Ctds-Roles 服务身份头
 * （evidence-service / evidence-internal，三域 yml 各自映射）、失败不重试 → empty
 * （调用方显式标 UNAVAILABLE 降级 + WARN，不伪装连续、不静默）。
 */
abstract class InternalTrailClientSupport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    static final String INTERNAL_SUBJECT = "evidence-service";
    static final String INTERNAL_ROLES = "evidence-internal";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    InternalTrailClientSupport(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** GET 内部端点并解析 ApiResult.data；任何失败（网络 / 非复位码 / 封套非成功）→ empty。 */
    <T> Optional<T> get(final Logger log, final String domain, final String baseUrl,
            final String path, final Class<T> type) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            final HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
                            .timeout(READ_TIMEOUT)
                            .header("Accept", "application/json")
                            .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                            .header("X-Ctds-Roles", INTERNAL_ROLES)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("audit trail client non-2xx: domain={}, status={}", domain,
                        response.statusCode());
                return Optional.empty();
            }
            final var root = objectMapper.readTree(response.body());
            if (!"0".equals(root.path("code").asText())) {
                log.warn("audit trail client non-success envelope: domain={}, code={}",
                        domain, root.path("code").asText());
                return Optional.empty();
            }
            return Optional.of(objectMapper.treeToValue(root.path("data"), type));
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.warn("audit trail client interrupted: domain={}", domain);
            return Optional.empty();
        } catch (final IOException | IllegalArgumentException failure) {
            log.warn("audit trail client unreachable: domain={}, reason={}", domain,
                    failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
