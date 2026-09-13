package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.subject.application.SubjectDetail;
import com.ctds.subject.application.SubjectRegistrationService;
import com.ctds.subject.interfaces.dto.CancellationView;
import com.ctds.subject.interfaces.dto.RegistrationView;
import com.ctds.subject.interfaces.dto.RegisterRequest;
import com.ctds.subject.interfaces.dto.SubjectView;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主体注册端点（WBS-3.1.2 hifi 接口契约）：注册/进度查询/撤销，权限注解强制（subject.register/read/cancel，
 * 未认证 401 / 无权限 403+DENIED 审计）。操作者取 AuthContext 当前身份，不收请求体传入。
 */
@RestController
@RequestMapping("/api/v1/subject/registrations")
public class SubjectRegistrationController {

    private final SubjectRegistrationService registrationService;

    public SubjectRegistrationController(final SubjectRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /** 提交注册/重报（幂等键 = 统一社会信用代码，重复请求返回首次结果——规格行为 1 第 3 条）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.register")
    public ApiResult<RegistrationView> register(@Valid @RequestBody final RegisterRequest request) {
        return ApiResult.ok(RegistrationView.from(registrationService.register(request.toCommand())));
    }

    /** 注册进度查询（注册信息脱敏展示 + 当前状态 + 流转记录）。 */
    @GetMapping(path = "/{subjectNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.read")
    public ApiResult<SubjectView> view(@PathVariable final String subjectNo) {
        final SubjectDetail detail = registrationService.detail(subjectNo);
        return ApiResult.ok(SubjectView.from(detail, maskPhone(detail.subject().contactPhone())));
    }

    /** 撤销申请（仅待认证可撤销；撤销后重新注册 = 同一申请编号重报，lofi Q3-A）。 */
    @PostMapping(path = "/{subjectNo}/cancellation", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.cancel")
    public ApiResult<CancellationView> cancel(@PathVariable final String subjectNo) {
        return ApiResult.ok(CancellationView.from(registrationService.cancel(subjectNo)));
    }

    /** 联系电话脱敏：保留前 3 后 4（规格行为 5 展示管控同源口径，分级规范 §5）。 */
    static String maskPhone(final String phone) {
        if (phone == null || phone.length() < 8) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
