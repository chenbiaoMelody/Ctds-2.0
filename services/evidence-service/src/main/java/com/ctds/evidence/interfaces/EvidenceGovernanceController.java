package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageResult;
import com.ctds.evidence.application.EvidenceDetailView;
import com.ctds.evidence.application.EvidenceOverviewView;
import com.ctds.evidence.application.EvidenceQueryService;
import com.ctds.evidence.application.EvidenceRecordView;
import com.ctds.evidence.application.EvidenceSearchQuery;
import com.ctds.evidence.application.EvidenceVerifyService;
import com.ctds.evidence.application.EvidenceVerifyView;
import com.ctds.evidence.application.EvidenceWriteService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外治理读面（hifi §1.2，经统一网关 {@code connector-evidence}）：权限点
 * {@code evidence.governance}（运营方治理档，V1.0 承载监管视角，规格 Q6-A / B5-4）。
 * 只读清单 / 详情 / 概览，以及核验与重试两个动作；无修改 / 删除 / 补录 / 手工填 txId 入口。
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceGovernanceController {

    private final EvidenceQueryService queryService;
    private final EvidenceVerifyService verifyService;
    private final EvidenceWriteService writeService;

    public EvidenceGovernanceController(final EvidenceQueryService queryService,
            final EvidenceVerifyService verifyService, final EvidenceWriteService writeService) {
        this.queryService = queryService;
        this.verifyService = verifyService;
        this.writeService = writeService;
    }

    /** 存证清单（至少一个过滤条件；时间窗 ≤90 天；分页 ≤100/页）。 */
    @GetMapping(path = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<PageResult<EvidenceRecordView>> list(
            @RequestParam(required = false) final String stageType,
            @RequestParam(required = false) final String businessNo,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String from,
            @RequestParam(required = false) final String to,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.page(new EvidenceSearchQuery(stageType, businessNo, status,
                from, to, pageNum, pageSize)));
    }

    /** 存证详情（记录 + 状态流转与核验留痕）。 */
    @GetMapping(path = "/records/{evidenceNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<EvidenceDetailView> detail(@PathVariable final String evidenceNo) {
        return ApiResult.ok(queryService.detail(evidenceNo));
    }

    /** 核验（三态结论；200 返回结论，不一致与未上链都不是 HTTP 错误）。 */
    @PostMapping(path = "/records/{evidenceNo}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<EvidenceVerifyView> verify(@PathVariable final String evidenceNo) {
        return ApiResult.ok(verifyService.verify(evidenceNo, AuthContext.subject()));
    }

    /** 重试上链（已上链 → 409 1012C0003；仍不可达 → 503 1012S0001 且记录保持 FAILED）。 */
    @PostMapping(path = "/records/{evidenceNo}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<EvidenceRecordView> retry(@PathVariable final String evidenceNo) {
        return ApiResult.ok(writeService.retry(evidenceNo, AuthContext.subject()));
    }

    /** 异常概览（待上链 / 失败 / 已上链计数 + 最近失败时点）。 */
    @GetMapping(path = "/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.governance")
    public ApiResult<EvidenceOverviewView> overview() {
        return ApiResult.ok(queryService.overview());
    }
}
