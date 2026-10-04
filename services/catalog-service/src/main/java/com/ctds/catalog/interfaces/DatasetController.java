package com.ctds.catalog.interfaces;

import com.ctds.catalog.application.DatasetCommandService;
import com.ctds.catalog.application.DatasetQueryService;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.interfaces.dto.CancellationRequest;
import com.ctds.catalog.interfaces.dto.CancellationView;
import com.ctds.catalog.interfaces.dto.DatasetActionLogView;
import com.ctds.catalog.interfaces.dto.PageViews;
import com.ctds.catalog.interfaces.dto.DatasetView;
import com.ctds.catalog.interfaces.dto.RegisterDatasetRequest;
import com.ctds.catalog.interfaces.dto.UpdateDatasetRequest;
import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源登记模型与服务端点（WBS-3.3.2 hifi §1.1 五端点；ADR-005 资源命名 /api/v1/data-spaces/{id}/datasets）。
 * 操作者身份取 AuthContext（X-Ctds-Subject 演示期身份头口径），不收请求体传入；
 * 权限点为功能第一道门槛（@RequirePermission：dataset.register/update/cancel/read），
 * 资源级判定（登记主体本人）由应用服务落定（判定输入含数据行，非注解静态门可表达）。
 * 写面幂等键为服务端派生（空间 + 登记主体 + 归一化名，hifi §4.2——沿 space 先例口径）。
 */
@RestController
@RequestMapping("/api/v1")
public class DatasetController {

    private final DatasetCommandService commandService;
    private final DatasetQueryService queryService;

    public DatasetController(final DatasetCommandService commandService,
            final DatasetQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** W1 登记（行为 1 全部规则；幂等键 = 空间+登记主体+归一化名，重复提交返回首次结果）。 */
    @PostMapping(path = "/data-spaces/{spaceId}/datasets", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.register")
    public ApiResult<DatasetView> register(@PathVariable final long spaceId,
            @RequestBody final RegisterDatasetRequest request) {
        final Dataset dataset = commandService.create(spaceId, request.toCommand());
        return ApiResult.ok(DatasetView.from(dataset));
    }

    /** W2 变更（行为 2 规则 1：仅登记主体本人；分级申报只能收紧就高）。 */
    @PutMapping(path = "/datasets/{datasetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.update")
    public ApiResult<DatasetView> update(@PathVariable final long datasetId,
            @RequestBody final UpdateDatasetRequest request) {
        final Dataset dataset = commandService.update(datasetId, request.toCommand());
        return ApiResult.ok(DatasetView.from(dataset));
    }

    /** W3 注销（行为 2 规则 2/3：二次确认强表达 + 两写事务 + 名称同空间锁定）。 */
    @PostMapping(path = "/datasets/{datasetId}/cancellation", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.cancel")
    public ApiResult<CancellationView> cancel(@PathVariable final long datasetId,
            @RequestBody(required = false) final CancellationRequest request) {
        final Boolean confirm = request == null ? null : request.confirmCancellation();
        final Dataset dataset = commandService.cancel(datasetId, confirm);
        return ApiResult.ok(CancellationView.from(dataset));
    }

    /** R1 本人资源分页列表（行为 7 规则 1：恒仅本人资源；spaceId 可选过滤）。 */
    @GetMapping(path = "/datasets/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.read")
    public ApiResult<PageResult<DatasetView>> mine(@RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final Long spaceId) {
        final PageResult<Dataset> result = queryService.mine(pageNum, pageSize, spaceId);
        final PageResult<DatasetView> views = new PageResult<>(
                result.list().stream().map(DatasetView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
        return ApiResult.ok(views);
    }

    /** R2 本人资源详情（行为 7 规则 2：非本人与不存在同形拒绝——1007C0005 防枚举）。 */
    @GetMapping(path = "/datasets/{datasetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.read")
    public ApiResult<DatasetView> detail(@PathVariable final long datasetId) {
        return ApiResult.ok(DatasetView.from(queryService.detail(datasetId)));
    }

    /**
     * R14 资源操作留痕分页（WBS-3.3.6 hifi §1.2，Q2-A；只读既有 dataset_action_log）：
     * 读面 = 登记主体本人 或 治理例外（catalog.governance，应用服务行级判定）；
     * 非本人与不存在 → 1007C0005 同形（非本人命中写 DENIED_READ 恰 1 行，沿 R2 先例）。
     */
    @GetMapping(path = "/datasets/{datasetId}/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("dataset.read")
    public ApiResult<PageResult<DatasetActionLogView>> actionLogs(@PathVariable final long datasetId,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(PageViews.page(
                queryService.actionLogs(datasetId, PageQuery.of(pageNum, pageSize, null)),
                DatasetActionLogView::from));
    }
}
