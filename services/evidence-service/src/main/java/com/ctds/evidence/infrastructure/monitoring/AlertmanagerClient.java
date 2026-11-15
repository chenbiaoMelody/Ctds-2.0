package com.ctds.evidence.infrastructure.monitoring;

import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.AlertSilencePort;
import com.ctds.evidence.domain.MonitorErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Alertmanager HTTP API 适配器（WBS-3.9.4；实现 {@link AlertSilencePort}——处置静默链路
 * 唯一依赖，告警列表读面不依赖本客户端，Q4-A）。JDK HttpClient 沿例常量；base-url 经 @Value
 * 注入（沿 SpaceTrailClient 先例，缺省空 = 未配置）。请求体形状 = v0.34.0 官方 OpenAPI
 * `postableSilence`（P7 核对 + 真机 422 佐证）：matchers（必填，minItems 1）/ startsAt /
 * endsAt / createdBy / comment（均必填）；响应 = `{ "silenceID": string }`（字段名精确大小写）。
 * 不可达 / 非 2xx → `1013S0003`（fail-closed）。
 */
@Component
public class AlertmanagerClient implements AlertSilencePort {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerClient.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public AlertmanagerClient(
            @Value("${ctds.evidence.monitor.alertmanager.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public String createSilence(final String alertName, final Instant startsAt,
            final Instant endsAt, final String createdBy, final String comment) {
        if (baseUrl.isBlank()) {
            throw new BizException(MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE,
                    MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE_MESSAGE);
        }
        final ObjectNode matcher = objectMapper.createObjectNode();
        matcher.put("name", "alertname");
        matcher.put("value", alertName);
        matcher.put("isRegex", false);
        matcher.put("isEqual", true);
        final ArrayNode matchers = objectMapper.createArrayNode();
        matchers.add(matcher);
        final ObjectNode body = objectMapper.createObjectNode();
        body.set("matchers", matchers);
        body.put("startsAt", startsAt.toString());
        body.put("endsAt", endsAt.toString());
        body.put("createdBy", createdBy);
        body.put("comment", comment);
        try {
            final HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v2/silences"))
                            .timeout(READ_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("alertmanager client non-2xx: status={}", response.statusCode());
                throw unavailable();
            }
            final JsonNode root = objectMapper.readTree(response.body());
            final String silenceId = root.path("silenceID").asText(null);
            if (silenceId == null || silenceId.isBlank()) {
                log.warn("alertmanager client missing silenceID in response");
                throw unavailable();
            }
            return silenceId;
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable(failure);
        } catch (final IOException | IllegalArgumentException failure) {
            log.warn("alertmanager client unreachable: reason={}", failure.getClass().getSimpleName());
            throw unavailable(failure);
        }
    }

    private static BizException unavailable() {
        return new BizException(MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE,
                MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE_MESSAGE);
    }

    private static BizException unavailable(final Throwable cause) {
        return new BizException(MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE,
                MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE_MESSAGE, cause);
    }
}
