package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.SandboxCommand;
import com.ctds.delivery.application.SandboxCommandService;
import com.ctds.delivery.application.SandboxJobService;
import com.ctds.delivery.application.SandboxQueryService;
import com.ctds.delivery.application.SandboxRunCommand;
import com.ctds.delivery.interfaces.dto.SandboxRequest.CreateRequest;
import com.ctds.delivery.interfaces.dto.SandboxRequest.RunJobRequest;
import com.ctds.delivery.interfaces.dto.SandboxViews.JobView;
import com.ctds.delivery.interfaces.dto.SandboxViews.ResultView;
import com.ctds.delivery.interfaces.dto.SandboxViews.SandboxView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱端点（hifi §7.1，资源名 sandboxes）：申请开通 / 参与者读面 / 主动关闭 / 受控作业 /
 * 作业历史 / 结果元数据。身份取 AuthContext；一切可见性判定在服务端。
 */
@RestController
@RequestMapping("/api/v1/sandboxes")
public class SandboxController {

    private final SandboxCommandService commandService;
    private final SandboxJobService jobService;
    private final SandboxQueryService queryService;

    public SandboxController(final SandboxCommandService commandService,
            final SandboxJobService jobService, final SandboxQueryService queryService) {
        this.commandService = commandService;
        this.jobService = jobService;
        this.queryService = queryService;
    }

    /** 申请开通（幂等键必填；缺失 / 非法由服务层收敛 1009C0005）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.request")
    public ApiResult<SandboxView> create(
            @RequestHeader(value = "X-Idempotency-Key", required = false) final String idempotencyKey,
            @RequestBody final CreateRequest request) {
        return ApiResult.ok(commandService.create(new SandboxCommand(AuthContext.subject(),
                request == null ? null : request.contractNo(), idempotencyKey)));
    }

    /** 参与者详情（需求方 / 提供方；非参与方 / 不存在同码同文 1009C0010）。 */
    @GetMapping(path = "/{sandboxNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<SandboxView> get(@PathVariable final String sandboxNo) {
        return ApiResult.ok(queryService.get(sandboxNo, AuthContext.subject()));
    }

    /** 参与者列表（pageSize 上限 100；orderBy 白名单）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<PageResult<SandboxView>> list(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final String orderBy) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, orderBy);
        return ApiResult.ok(queryService.list(AuthContext.subject(), contractNo, status, page));
    }

    /** 需求方主动关闭（幂等：已终止任务返回当前视图）。 */
    @PostMapping(path = "/{sandboxNo}/actions/close", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.use")
    public ApiResult<SandboxView> close(@PathVariable final String sandboxNo) {
        return ApiResult.ok(commandService.close(sandboxNo, AuthContext.subject()));
    }

    /** 受控作业（幂等键必填；请求只给清单内 jobId）。 */
    @PostMapping(path = "/{sandboxNo}/actions/run-job",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.use")
    public ApiResult<JobView> runJob(@PathVariable final String sandboxNo,
            @RequestHeader(value = "X-Idempotency-Key", required = false) final String idempotencyKey,
            @RequestBody final RunJobRequest request) {
        return ApiResult.ok(jobService.run(new SandboxRunCommand(sandboxNo,
                request == null ? null : request.jobId(),
                request == null ? null : request.purpose(),
                request == null ? null : request.territory(),
                AuthContext.subject(), idempotencyKey)));
    }

    /** 作业历史（参与者；分页）。 */
    @GetMapping(path = "/{sandboxNo}/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<PageResult<JobView>> jobs(@PathVariable final String sandboxNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.jobs(sandboxNo, AuthContext.subject(),
                PageQuery.of(pageNum, pageSize, null)));
    }

    /** 结果物元数据（无内容、无路径）。 */
    @GetMapping(path = "/{sandboxNo}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<List<ResultView>> results(@PathVariable final String sandboxNo) {
        return ApiResult.ok(queryService.results(sandboxNo, AuthContext.subject()));
    }
}
