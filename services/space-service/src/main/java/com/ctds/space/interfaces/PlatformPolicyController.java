package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.application.SpacePolicyService;
import com.ctds.space.domain.SpacePolicy;
import com.ctds.space.interfaces.dto.PolicyEntryView;
import com.ctds.space.interfaces.dto.PolicyRequests.PlatformPolicyCreateRequest;
import com.ctds.space.interfaces.dto.PolicyRequests.PlatformPolicyUpdateRequest;
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
 * 平台级策略条目治理端点（WBS-3.2.5 hifi §1 端点 1~3，ADR-005 复数资源命名独立顶层——
 * 平台条目不属于任何空间，不挂 /data-spaces/{id}）：创建 / 值与红线变更 / 治理面列表。
 * 权限点 platform.policy（角色头映射来源，无空间上下文；越权 → 1006C0007 + DENIED 留痕）。
 */
@RestController
@RequestMapping("/api/v1/platform-policies")
public class PlatformPolicyController {

    private final SpacePolicyService policyService;

    public PlatformPolicyController(final SpacePolicyService policyService) {
        this.policyService = policyService;
    }

    /** 端点 1 平台条目创建（键须在目录、值须在值域、同键至多一条 ACTIVE → 1006C0012）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PolicyEntryView> create(@RequestBody final PlatformPolicyCreateRequest request) {
        return ApiResult.ok(PolicyEntryView.from(policyService.createPlatformEntry(
                request.entryKey(), request.entryValue(), request.redline())));
    }

    /** 端点 2 平台条目值与红线变更（键不可变更；至少一项；定位失败 1006C0014 同形防探测）。 */
    @PutMapping(path = "/{entryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PolicyEntryView> update(@PathVariable final long entryId,
            @RequestBody final PlatformPolicyUpdateRequest request) {
        return ApiResult.ok(PolicyEntryView.from(policyService.updatePlatformEntry(entryId,
                request.entryValue(), request.redline())));
    }

    /** 端点 3 平台条目列表（治理面只读）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<PolicyEntryView>> list(
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final PageResult<SpacePolicy> result = policyService.listPlatformEntries(
                PageQuery.of(pageNum, pageSize, null));
        return ApiResult.ok(new PageResult<>(result.list().stream().map(PolicyEntryView::from).toList(),
                result.total(), result.pageNum(), result.pageSize(), result.totalPages()));
    }
}
