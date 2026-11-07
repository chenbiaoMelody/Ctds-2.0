package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.application.EvidenceAuditAccessGuard;
import com.ctds.evidence.application.EvidenceAuditOverviewService;
import com.ctds.evidence.application.EvidenceAuditReportRequest;
import com.ctds.evidence.application.EvidenceAuditViews;
import com.ctds.evidence.application.EvidenceReportService;
import com.ctds.evidence.application.EvidenceTrailService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监管审计读面 5 端点（WBS-3.8.4 hifi §1 E1~E5，统一前缀 /api/v1/evidence/audit）：
 * 总览矩阵 / 异常项清单 / 全链时间线与日志调阅 / 报告生成 / 报告下载。
 * 权限口径 = 审计访问守卫（hifi §5.1，方法首行 EvidenceAuditAccessGuard；非治理档与
 * "对象不存在"统一 400 + 1000C0003 同码同文防枚举，Q4-A）——不挂 @RequirePermission
 * 以避免 403 抢先（3.8.2 既有 5 端点 403 口径零改动共存）。
 * 只读铁律：全部端点为查询语义，无改 / 删 / 补录 / 重放入口（B5-3 / B8-3）。
 */
@RestController
@RequestMapping("/api/v1/evidence/audit")
public class EvidenceAuditController {

    private final EvidenceAuditAccessGuard guard;
    private final EvidenceAuditOverviewService overviewService;
    private final EvidenceTrailService trailService;
    private final EvidenceReportService reportService;

    public EvidenceAuditController(final EvidenceAuditAccessGuard guard,
            final EvidenceAuditOverviewService overviewService,
            final EvidenceTrailService trailService, final EvidenceReportService reportService) {
        this.guard = guard;
        this.overviewService = overviewService;
        this.trailService = trailService;
        this.reportService = reportService;
    }

    /** E1 总览：四环节 × 三状态矩阵 + 异常三计数 + 快照时点（from/to 可选，≤90 天）。 */
    @GetMapping(path = "/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<EvidenceAuditViews.OverviewView> overview(
            @RequestParam(required = false) final String from,
            @RequestParam(required = false) final String to) {
        guard.requireGovernanceAccess();
        return ApiResult.ok(overviewService.overview(from, to));
    }

    /** E2 异常项清单：三类并集分页（PENDING / FAILED / MISMATCH；MISMATCH 附最新核验结论与核验人）。 */
    @GetMapping(path = "/anomalies", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<EvidenceAuditViews.AnomalyItemView>> anomalies(
            @RequestParam(required = false) final String from,
            @RequestParam(required = false) final String to,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        guard.requireGovernanceAccess();
        return ApiResult.ok(overviewService.anomalies(from, to, pageNum, pageSize));
    }

    /** E3 全链时间线与日志调阅：businessNo 命中 = 单笔全链时间线；其余条件 = 清单分页。 */
    @GetMapping(path = "/trails", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<EvidenceAuditViews.TrailView> trails(
            @RequestParam(required = false) final String stageType,
            @RequestParam(required = false) final String businessNo,
            @RequestParam(required = false) final String subjectDigest,
            @RequestParam(required = false) final String from,
            @RequestParam(required = false) final String to,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        guard.requireGovernanceAccess();
        return ApiResult.ok(trailService.trails(stageType, businessNo, subjectDigest,
                from, to, pageNum, pageSize));
    }

    /** E4 报告生成：条件 ≥1、窗 ≤90 天、清单 ≤10000；重复生成 = 新编号不覆盖。 */
    @PostMapping(path = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<EvidenceAuditViews.ReportView> generateReport(
            @RequestBody final EvidenceAuditReportRequest request) {
        guard.requireGovernanceAccess();
        return ApiResult.ok(reportService.generate(request.stageType(), request.businessNo(),
                request.subjectDigest(), request.from(), request.to(), request.remark()));
    }

    /** E5 报告下载：重算 SM3 核验（不一致 = 1012S0003 不输出文件）→ 登记快照 CSV/JSON + 导出留痕。 */
    @GetMapping(path = "/reports/{reportNo}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable final String reportNo,
            @RequestParam(required = false) final String format) {
        guard.requireGovernanceAccess();
        final EvidenceReportService.Export export = reportService.export(reportNo, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.fileName() + "\"")
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.body().getBytes(StandardCharsets.UTF_8));
    }
}
