package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.application.SpaceCommandService;
import com.ctds.space.application.SpaceQueryService;
import com.ctds.space.domain.Space;
import com.ctds.space.interfaces.dto.CreateSpaceRequest;
import com.ctds.space.interfaces.dto.DissolveRequest;
import com.ctds.space.interfaces.dto.SpaceSummaryView;
import com.ctds.space.interfaces.dto.UpdateSpaceRequest;
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
 * 空间生命周期端点（WBS-3.2.3 hifi §1 端点契约表，ADR-005 资源命名 /api/v1/data-spaces）。
 * 操作者身份取 AuthContext（X-Ctds-Subject 演示期身份头口径），不收请求体传入；
 * 权限判定为应用服务双轨动态判定（owner/admin 成员表 + platform.operator 角色头），不经注解静态门。
 */
@RestController
@RequestMapping("/api/v1/data-spaces")
public class SpaceController {

    private final SpaceCommandService commandService;
    private final SpaceQueryService queryService;

    public SpaceController(final SpaceCommandService commandService, final SpaceQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** 端点 1 创建（行为 1 全部规则；幂等键 = 所有者+归一化名，重复提交返回首次结果）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> create(
            @RequestBody final CreateSpaceRequest request) {
        final Space created = commandService.create(request.toCommand());
        return ApiResult.ok(detailView(created.id()));
    }

    /** 端点 2 启用（CREATED→ACTIVE；启用前提 = 所有者仍 ADMITTED）。 */
    @PostMapping(path = "/{id}/enablement", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> enable(@PathVariable final long id) {
        commandService.enable(id);
        return ApiResult.ok(detailView(id));
    }

    /** 端点 3 冻结（ACTIVE→FROZEN）。 */
    @PostMapping(path = "/{id}/freezing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> freeze(@PathVariable final long id) {
        commandService.freeze(id);
        return ApiResult.ok(detailView(id));
    }

    /** 端点 4 恢复（FROZEN→ACTIVE）。 */
    @PostMapping(path = "/{id}/unfreezing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> unfreeze(@PathVariable final long id) {
        commandService.unfreeze(id);
        return ApiResult.ok(detailView(id));
    }

    /** 端点 5 解散（任一非终态→DISSOLVED 不可逆；confirmDissolve 显式 true 必填 + 三写同事务）。 */
    @PostMapping(path = "/{id}/dissolution", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> dissolve(@PathVariable final long id,
            @RequestBody(required = false) final DissolveRequest request) {
        final Boolean confirm = request == null ? null : request.confirmDissolve();
        final String reason = request == null ? null : request.reason();
        commandService.dissolve(id, confirm, reason);
        return ApiResult.ok(detailView(id));
    }

    /** 端点 6 配置变更（Q5-A 白名单：仅简介与生效期；白名单外字段 400 拒绝）。 */
    @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SpaceSummaryView.SpaceDetailView> update(@PathVariable final long id,
            @RequestBody final UpdateSpaceRequest request) {
        if (!request.withinWhitelist()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "请求包含不可变更字段（仅接受空间简介与生效期）");
        }
        commandService.update(id, new SpaceCommandService.SpaceUpdateRequest(request.getIntro(),
                request.getEffectiveFrom(), request.getEffectiveTo()));
        return ApiResult.ok(detailView(id));
    }

    /** 端点 7 检索列表（登录主体仅 PUBLIC；platform.operator 全量；终态不出现在可检索面）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<SpaceSummaryView>> list(@RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final String keyword) {
        final PageResult<Space> result = queryService.list(pageNum, pageSize, keyword);
        final PageResult<SpaceSummaryView> views = new PageResult<>(
                result.list().stream().map(SpaceSummaryView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages());
        return ApiResult.ok(views);
    }

    /** 端点 8 详情（成员/owner/admin/运营方全量含成员构成；非成员仅 PUBLIC 摘要；不公开=不可见）。 */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<?> detail(@PathVariable final long id) {
        final SpaceQueryService.SpaceView view = queryService.detail(id);
        if (view.fullDetail()) {
            return ApiResult.ok(SpaceSummaryView.SpaceDetailView.from(view.space(), view.members()));
        }
        return ApiResult.ok(SpaceSummaryView.from(view.space()));
    }

    private SpaceSummaryView.SpaceDetailView detailView(final long id) {
        final SpaceQueryService.SpaceView view = queryService.detail(id);
        return SpaceSummaryView.SpaceDetailView.from(view.space(), view.members());
    }
}
