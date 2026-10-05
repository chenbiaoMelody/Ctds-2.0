package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.ContractTemplateAppService;
import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.domain.TemplateVersion;
import com.ctds.contract.interfaces.dto.CreateTemplateRequest;
import com.ctds.contract.interfaces.dto.ManageTemplateView;
import com.ctds.contract.interfaces.dto.RevisionView;
import com.ctds.contract.interfaces.dto.ReviseTemplateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板运营写面端点（WBS-3.4.2 hifi §2.1 W1~W4；ADR-005 动词子资源）。
 * 操作者身份取 AuthContext（X-Ctds-Subject 演示期身份头口径），不收请求体传入；
 * 注解权限点 = contract.template.read（功能第一道门槛：未认证 401 在注解层挡——
 * **维护权唯一的判定与拒绝留痕在应用服务单点承载**，hifi V1.1 §2.1 补正口径：
 * 注解层拒绝发生在控制器之前、无法写行为 1 规则 1 要求的 DENIED 留痕）。
 * 写面幂等键为服务端派生（新增 = 操作者+类型+归一化名；修订 = 操作者+模板号+框架哈希）。
 */
@RestController
@RequestMapping("/api/v1")
public class ContractTemplateOpsController {

    private final ContractTemplateAppService appService;

    public ContractTemplateOpsController(final ContractTemplateAppService appService) {
        this.appService = appService;
    }

    /** W1 新增模板（含首版本 V1；幂等重放返回首次结果）。 */
    @PostMapping(path = "/contract-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ManageTemplateView> create(@RequestBody final CreateTemplateRequest request) {
        final ContractTemplate template = appService.create(request.toCommand(AuthContext.subject()));
        return ApiResult.ok(ManageTemplateView.from(template));
    }

    /** W2 修订模板（出新版本 Vn+1，旧版本保留可查；幂等重放返回首次新版本）。 */
    @PostMapping(path = "/contract-templates/{templateNo}/revisions", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<RevisionView> revise(@PathVariable final String templateNo,
            @RequestBody final ReviseTemplateRequest request) {
        final TemplateVersion version = appService.revise(
                request.toCommand(AuthContext.subject(), templateNo));
        return ApiResult.ok(RevisionView.from(templateNo, version.versionNo()));
    }

    /** W3 停用模板（行为 1 规则 4：退出浏览 + 不可新发起，不影响既有——快照语义归 3.4.3）。 */
    @PostMapping(path = "/contract-templates/{templateNo}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    public ApiResult<ManageTemplateView> disable(@PathVariable final String templateNo) {
        return ApiResult.ok(ManageTemplateView.from(appService.disable(AuthContext.subject(), templateNo)));
    }

    /** W4 启用模板（剧本 C-4.1 S2-6：恢复可浏览、可发起）。 */
    @PostMapping(path = "/contract-templates/{templateNo}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.template.read")
    public ApiResult<ManageTemplateView> enable(@PathVariable final String templateNo) {
        return ApiResult.ok(ManageTemplateView.from(appService.enable(AuthContext.subject(), templateNo)));
    }
}
