package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.evidence.application.AlertDisposeService;
import com.ctds.evidence.application.AlertQueryService;
import com.ctds.evidence.application.MonitorQueryService;
import com.ctds.evidence.application.MonitorViews.AlertsView;
import com.ctds.evidence.application.MonitorViews.DisposeRequest;
import com.ctds.evidence.application.MonitorViews.DisposeView;
import com.ctds.evidence.application.MonitorViews.MetricsView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-3.9.4 Q1-A：平台监控读数读面 + 运维处置（平台管理读面既有落点 = evidence-service，
 * 沿 DomainGovernanceController 惯例；/api/v1/platform/** 网关路由已有，零路由改动）。
 * 读面显式挂 `platform.monitor.read`（B5-R3 只读，仅 GET，无指标写入口）；处置显式挂
 * `platform.alert.handle`（B8-R1 处置通道唯一性 = 唯一鉴权 + 留痕入口；B8-R3 仅系统管理员）。
 * 处置人 = X-Ctds-Subject 头（留痕四要素之处置人，经网关剥离重建，不可伪造直连）。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformMonitorController {

    private final MonitorQueryService monitorQueryService;
    private final AlertQueryService alertQueryService;
    private final AlertDisposeService alertDisposeService;

    public PlatformMonitorController(final MonitorQueryService monitorQueryService,
            final AlertQueryService alertQueryService, final AlertDisposeService alertDisposeService) {
        this.monitorQueryService = monitorQueryService;
        this.alertQueryService = alertQueryService;
        this.alertDisposeService = alertDisposeService;
    }

    /** 平台性能监控读数（四区块 + 未接入显式标注；只读，B5-R3）。 */
    @GetMapping(path = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.monitor.read")
    public ApiResult<MetricsView> metrics() {
        return ApiResult.ok(monitorQueryService.metrics());
    }

    /** 告警列表（封闭两规则，要素七项齐备 + 状态映射 B7-R1；只读）。 */
    @GetMapping(path = "/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.monitor.read")
    public ApiResult<AlertsView> alerts() {
        return ApiResult.ok(alertQueryService.alerts());
    }

    /** 运维处置（确认/静默/升级；唯一入口，均留痕 alert.dispose）。 */
    @PostMapping(path = "/alerts/actions", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("platform.alert.handle")
    public ApiResult<DisposeView> dispose(
            @RequestHeader(value = "X-Ctds-Subject", required = false) final String subject,
            @RequestBody final DisposeRequest request) {
        return ApiResult.ok(alertDisposeService.dispose(subject, request));
    }
}
