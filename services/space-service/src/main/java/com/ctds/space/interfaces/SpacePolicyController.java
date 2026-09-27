package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.space.application.SpacePolicyService;
import com.ctds.space.domain.EffectivePolicyResolver.EffectivePolicyRow;
import com.ctds.space.interfaces.dto.EffectivePolicyItemView;
import com.ctds.space.interfaces.dto.PolicyOverrideView;
import com.ctds.space.interfaces.dto.PolicyRequests.PolicyOverrideRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间级策略继承与覆盖端点（WBS-3.2.5 hifi §1 端点 4~5，挂既有 /api/v1/data-spaces 前缀）：
 * 覆盖提交（space.admin；红线放宽拒 1006C0013——行为 7 规则 2 代码强制）+ 有效策略视图
 * （space.member / 运营方；解散后仅 owner 与运营方可查——规则 5 生命周期联动）。
 */
@RestController
@RequestMapping("/api/v1/data-spaces")
public class SpacePolicyController {

    private final SpacePolicyService policyService;

    public SpacePolicyController(final SpacePolicyService policyService) {
        this.policyService = policyService;
    }

    /** 端点 4 空间覆盖提交（owner/admin；空间须 ACTIVE；响应回显生效结果与来源标注）。 */
    @PostMapping(path = "/{id}/policies/overrides", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PolicyOverrideView> submitOverride(@PathVariable final long id,
            @RequestBody final PolicyOverrideRequest request) {
        final EffectivePolicyRow row = policyService.submitOverride(id, request.entryKey(),
                request.entryValue());
        return ApiResult.ok(PolicyOverrideView.from(row));
    }

    /** 端点 5 有效策略视图（取严解析 + 来源三态标注；归档空间可查不可变）。 */
    @GetMapping(path = "/{id}/policies/effective", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<EffectivePolicyItemView>> effectivePolicy(@PathVariable final long id) {
        return ApiResult.ok(EffectivePolicyItemView.fromList(policyService.effectivePolicy(id)));
    }
}
