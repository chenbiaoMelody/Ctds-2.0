package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.application.SpaceQueryService;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.interfaces.dto.SpaceActionLogView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间操作留痕只读端点（WBS-3.2.6 hifi §5 端点 25，挂既有 /api/v1/data-spaces 前缀）：
 * 权限与端点 24 同口径（ACTIVE/FROZEN = 空间成员或 platform.operator；DISSOLVED = 仅 owner 或
 * platform.operator），无权 → 1006C0007 + 拒绝留痕；零新增错误码、零迁移、既有端点零改动。
 */
@RestController
@RequestMapping("/api/v1/data-spaces")
public class SpaceActionLogController {

    private final SpaceQueryService queryService;

    public SpaceActionLogController(final SpaceQueryService queryService) {
        this.queryService = queryService;
    }

    /** 端点 25 空间操作留痕（分页：created_at 倒序 + id 次序稳定；出参字段白名单见 SpaceActionLogView）。 */
    @GetMapping(path = "/{id}/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<SpaceActionLogView>> actionLogs(@PathVariable final long id,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<SpaceActionLog> result = queryService.actionLogs(id,
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(result.list().stream().map(SpaceActionLogView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages()));
    }
}
