package com.ctds.evidence.infrastructure.monitoring;

import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.MonitorErrorCodes;
import com.ctds.evidence.domain.MonitorSourcePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prometheus HTTP API 适配器（WBS-3.9.4 hifi §1；实现 {@link MonitorSourcePort}——应用层只依赖
 * 端口，换源只换适配器）。JDK HttpClient（connect 2s / read 3s，沿 InternalTrailClientSupport
 * 常量先例）；base-url 经 @Value 注入（沿 SpaceTrailClient 先例，缺省空 = 未配置）。
 * 响应形状 P5 实测（Prometheus v3.13.3，build-output/w394-precheck-data/ 原件）：
 * value = [ts, "字符串数值"]；base-url 空 / 不可达 / 非 2xx / 封套非 success → `1013S0003`
 * （fail-closed，不伪造空数据 / 零值，hifi §4 边界 1）。只读（无任何指标写入口，B5-3）。
 */
@Component
public class PrometheusClient implements MonitorSourcePort {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public PrometheusClient(
            @Value("${ctds.evidence.monitor.prometheus.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public Optional<String> queryScalar(final String promql) {
        final JsonNode data = fetch("/api/v1/query?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        final JsonNode results = data.path("result");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        final String value = results.get(0).path("value").path(1).asText();
        if (value.isBlank() || !isFiniteNumber(value)) {
            // NaN / +Inf / -Inf（无样本或溢出）→ empty：读面按 null 呈现，不显示非法数值（评审① P3）
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static boolean isFiniteNumber(final String value) {
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (final NumberFormatException failure) {
            return false;
        }
    }

    @Override
    public List<AlertRuleSnapshot> alertRules() {
        final Map<String, AlertRuleSnapshot> byName = new LinkedHashMap<>();
        final JsonNode data = fetch("/api/v1/rules");
        for (final JsonNode group : data.path("groups")) {
            for (final JsonNode rule : group.path("rules")) {
                final String name = rule.path("name").asText();
                final JsonNode labels = rule.path("labels");
                byName.put(name, new AlertRuleSnapshot(name, rule.path("state").asText(),
                        rule.path("query").asText(), labels.path("severity").asText(null),
                        targetJob(rule, null), null));
            }
        }
        // 触发时点 = /api/v1/alerts 活跃实例最早 activeAt（多实例取最早，B7-1 要素）
        final JsonNode alerts = fetch("/api/v1/alerts");
        for (final JsonNode alert : alerts.path("alerts")) {
            final String name = alert.path("labels").path("alertname").asText(null);
            final String activeAt = alert.path("activeAt").asText(null);
            final AlertRuleSnapshot snapshot = byName.get(name);
            if (snapshot == null || activeAt == null) {
                continue;
            }
            final String earliest = snapshot.activeAt() == null
                    || activeAt.compareTo(snapshot.activeAt()) < 0 ? activeAt : snapshot.activeAt();
            byName.put(name, new AlertRuleSnapshot(snapshot.name(), snapshot.state(),
                    snapshot.query(), snapshot.severity(), targetJob(alert, snapshot), earliest));
        }
        return List.copyOf(byName.values());
    }

    private static String targetJob(final JsonNode source, final AlertRuleSnapshot fallback) {
        final String job = source.path("labels").path("job").asText(null);
        if (job != null && !job.isBlank()) {
            return job;
        }
        return fallback == null ? null : fallback.targetJob();
    }

    private JsonNode fetch(final String pathWithQuery) {
        if (baseUrl.isBlank()) {
            throw new BizException(MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE,
                    MonitorErrorCodes.MONITORING_SOURCE_UNAVAILABLE_MESSAGE);
        }
        try {
            final HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + pathWithQuery))
                            .timeout(READ_TIMEOUT)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("prometheus client non-2xx: path={}, status={}",
                        pathWithQuery, response.statusCode());
                throw unavailable();
            }
            final JsonNode root = objectMapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                log.warn("prometheus client non-success: status={}", root.path("status").asText());
                throw unavailable();
            }
            return root.path("data");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable(failure);
        } catch (final IOException | IllegalArgumentException failure) {
            log.warn("prometheus client unreachable: reason={}", failure.getClass().getSimpleName());
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
