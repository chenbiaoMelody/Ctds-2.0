package com.ctds.evidence.application;

import com.ctds.evidence.application.MonitorViews.MetricsView;
import com.ctds.evidence.application.MonitorViews.MonitorServiceView;
import com.ctds.evidence.domain.MonitorSourcePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 监控读数组装（WBS-3.9.4 hifi §1）：逐服务四区块查询（PromQL 与看板 / ADR-014 §3.5 同源
 * 口径，指标名 P5 实测）+ 未接入判定（`up` 无 series → NOT_INTEGRATED + note 显式标注，
 * 值恒 null 不显示 0 / 绿，B5-4）。数据源不可用沿 {@link MonitorSourcePort} fail-closed 抛出。
 */
@Service
public class MonitorQueryService {

    /** 存活（未接入判定依据；up==0 = 已接入但不可达 DOWN）。 */
    static final String Q_UP = "up{job=\"%s\"}";
    /** JVM 堆内存与 GC（看板区块 2 同源：used 求和；max 取最大堆区上限——P5 实测 G1 未定义区为 -1，负值归 null）。 */
    static final String Q_HEAP_USED = "sum(jvm_memory_used_bytes{job=\"%s\",area=\"heap\"})";
    static final String Q_HEAP_MAX = "max(jvm_memory_max_bytes{job=\"%s\",area=\"heap\"})";
    static final String Q_GC_RATE = "rate(jvm_gc_pause_seconds_sum{job=\"%s\"}[5m])";
    /** HTTP 速率（看板区块 3 同源，1m 窗口）。 */
    static final String Q_HTTP_RATE = "sum(rate(http_server_requests_seconds_count{job=\"%s\"}[1m]))";
    /** 5xx 错误率（看板区块 4 同源，百分比）与 P95 时延（秒 → 读面 ms）。 */
    static final String Q_ERROR_RATE_5XX =
            "100 * sum(rate(http_server_requests_seconds_count{job=\"%s\",status=~\"5..\"}[5m]))"
                    + " / sum(rate(http_server_requests_seconds_count{job=\"%s\"}[5m]))";
    static final String Q_P95 =
            "quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"%s\"}[5m])))";

    private static final String ALIVE_UP = "UP";
    private static final String ALIVE_DOWN = "DOWN";

    private final MonitorSourcePort monitorSource;
    private final MonitorProperties properties;

    public MonitorQueryService(final MonitorSourcePort monitorSource,
            final MonitorProperties properties) {
        this.monitorSource = monitorSource;
        this.properties = properties;
    }

    /** 四区块读数（配置顺序呈现）；数据源不可用整体 503（fail-closed，不做部分降级假绿）。 */
    public MetricsView metrics() {
        final List<MonitorServiceView> rows = new ArrayList<>();
        for (final Map.Entry<String, String> entry : properties.serviceJobMap().entrySet()) {
            rows.add(row(entry.getKey(), entry.getValue()));
        }
        return new MetricsView(List.copyOf(rows));
    }

    private MonitorServiceView row(final String service, final String job) {
        final Optional<String> up = monitorSource.queryScalar(Q_UP.formatted(job));
        if (up.isEmpty()) {
            return new MonitorServiceView(service, job, MonitorViews.NOT_INTEGRATED,
                    null, null, null, null, null, null, null, MonitorViews.NOT_INTEGRATED_NOTE);
        }
        final String alive = parseDouble(up).map(value -> value > 0 ? ALIVE_UP : ALIVE_DOWN)
                .orElse(ALIVE_DOWN);
        final Long heapUsed = longOf(monitorSource.queryScalar(Q_HEAP_USED.formatted(job)));
        final Long heapMax = heapMaxOf(monitorSource.queryScalar(Q_HEAP_MAX.formatted(job)));
        final Double gcRate = doubleOf(monitorSource.queryScalar(Q_GC_RATE.formatted(job)));
        final Double httpRate = doubleOf(monitorSource.queryScalar(Q_HTTP_RATE.formatted(job)));
        final Double errorRate = doubleOf(monitorSource.queryScalar(Q_ERROR_RATE_5XX.formatted(job, job)));
        final Optional<Double> p95Seconds = parseDouble(monitorSource.queryScalar(Q_P95.formatted(job)));
        final Double p95Ms = p95Seconds.map(seconds -> seconds * 1000d).orElse(null);
        return new MonitorServiceView(service, job, MonitorViews.INTEGRATED, alive,
                heapUsed, heapMax, gcRate, httpRate, errorRate, p95Ms, null);
    }

    /** 堆上限：数据源未定义（-1，P5 实测 G1 未定义区）→ null，不显示负值。 */
    private static Long heapMaxOf(final Optional<String> value) {
        return parseDouble(value).filter(bytes -> bytes >= 0)
                .map(Math::round).orElse(null);
    }

    private static Long longOf(final Optional<String> value) {
        return parseDouble(value).map(Math::round).orElse(null);
    }

    private static Double doubleOf(final Optional<String> value) {
        return parseDouble(value).orElse(null);
    }

    private static Optional<Double> parseDouble(final Optional<String> value) {
        return value.flatMap(text -> {
            try {
                return Optional.of(Double.parseDouble(text));
            } catch (final NumberFormatException failure) {
                return Optional.empty();
            }
        });
    }
}
