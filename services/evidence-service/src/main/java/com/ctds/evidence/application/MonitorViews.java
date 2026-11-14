package com.ctds.evidence.application;

import java.util.List;

/**
 * WBS-3.9.4 监控读面 / 告警列表 / 运维处置响应与请求视图（hifi §2 契约；多记录持有类，
 * 沿 connector `ConnectorViews` 先例）。字段名 = 契约原文，出站即契约。
 */
public final class MonitorViews {

    private MonitorViews() {
    }

    /** 集成状态二值：已接入 / 未接入（未接入行值恒 null + note 显式文字标注，B5-4）。 */
    public static final String INTEGRATED = "INTEGRATED";
    public static final String NOT_INTEGRATED = "NOT_INTEGRATED";
    /** 未接入行显式标注（hifi §2 契约原文）。 */
    public static final String NOT_INTEGRATED_NOTE = "未接入指标采集，读数不可用";

    /** 单服务四区块读数行（alive：UP/DOWN，未接入 = null；值字段未接入 = null 不显示 0）。 */
    public record MonitorServiceView(
            String service,
            String job,
            String integrated,
            String alive,
            Long heapUsedBytes,
            Long heapMaxBytes,
            Double gcPauseSecondsRate5m,
            Double httpRatePerSec1m,
            Double errorRate5xx5m,
            Double p95LatencyMs5m,
            String note) {
    }

    /** 监控读数读面响应（GET /api/v1/platform/metrics）。 */
    public record MetricsView(List<MonitorServiceView> services) {
    }

    /** 告警项七要素（B7-1）+ stateNote（raw 透传：pending = 条件满足等待窗口）。 */
    public record AlertItemView(
            String alertName,
            String severity,
            String target,
            String triggeredAt,
            Double currentValue,
            String threshold,
            String state,
            String stateNote) {
    }

    /** 告警列表读面响应（GET /api/v1/platform/alerts；封闭两规则固定入列，规则即告警项）。 */
    public record AlertsView(List<AlertItemView> alerts) {
    }

    /** 处置请求（POST /api/v1/platform/alerts/actions；durationMinutes 仅静默，缺省 30，上限 1440）。 */
    public record DisposeRequest(String alertName, String action, String note, Integer durationMinutes) {
    }

    /** 处置响应（result=OK；silenceId 仅静默；recordedAt = 处置时点 ISO-8601）。 */
    public record DisposeView(String alertName, String action, String result, String silenceId,
            String recordedAt) {
    }
}
