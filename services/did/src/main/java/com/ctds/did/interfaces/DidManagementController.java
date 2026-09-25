package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.did.application.DidManagementQueryService;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.VerificationLog;
import com.ctds.did.interfaces.dto.DidOperationLogView;
import com.ctds.did.interfaces.dto.DidRecordView;
import com.ctds.did.interfaces.dto.VerificationLogView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DID 管理面读数端点（WBS-3.1.11 hifi §2.1）：签发记录列表 / 单 DID 操作留痕 / 验证留痕列表，
 * 一律 `did.admin`（无身份 401、无权限 403，ADR-005 §3）。只读，不改既有端点语义；
 * 字面量路径（/records、/verification-logs）优先于既有 `/{did}` 解析模板。
 */
@RestController
@RequestMapping("/api/v1/did")
public class DidManagementController {

    private final DidManagementQueryService service;

    public DidManagementController(final DidManagementQueryService service) {
        this.service = service;
    }

    /** 签发记录列表（分页 + 主体编号/记录状态过滤；最新签发在前，无匹配返回空列表）。 */
    @GetMapping(path = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<PageResult<DidRecordView>> records(
            @RequestParam(name = "subjectNo", required = false) final String subjectNo,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "pageNum", required = false) final Integer pageNum,
            @RequestParam(name = "pageSize", required = false) final Integer pageSize) {
        final PageResult<DidIdentity> page = service.records(subjectNo, status,
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(
                page.list().stream().map(DidRecordView::from).toList(),
                page.total(), page.pageNum(), page.pageSize(), page.totalPages()));
    }

    /** 单 DID 操作留痕（不分页：单 DID 写入点至多签发/重签 + 吊销两行）。 */
    @GetMapping(path = "/records/{did}/operation-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<List<DidOperationLogView>> operationLogs(@PathVariable final String did) {
        final List<DidOperationLog> logs = service.operationLogs(did);
        return ApiResult.ok(logs.stream().map(DidOperationLogView::from).toList());
    }

    /** 验证留痕列表（分页 + 可选 DID 过滤；只含时间/DID/结果/原因，无数据原文）。 */
    @GetMapping(path = "/verification-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<PageResult<VerificationLogView>> verificationLogs(
            @RequestParam(name = "did", required = false) final String did,
            @RequestParam(name = "pageNum", required = false) final Integer pageNum,
            @RequestParam(name = "pageSize", required = false) final Integer pageSize) {
        final PageResult<VerificationLog> page = service.verificationLogs(did,
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(
                page.list().stream().map(VerificationLogView::from).toList(),
                page.total(), page.pageNum(), page.pageSize(), page.totalPages()));
    }
}
