package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.subject.application.SubjectRegistrationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部只读端点（WBS-3.1.9 hifi §5 实施修正 2026-09-22 + 评审②P2-1 权限收敛 2026-09-23）：
 * 供 DID 绑定核验查询主体入驻状态。仅返回状态字段（最小暴露）；功能门槛 = **服务身份专用权限点**
 * {@code subject.internal.read}（仅授予 did-internal，申请人/审核员均不持有，防状态枚举）；
 * 不落归属断言（内部只读面，诚实边界登记 ADR-017 补记）——避免为服务读放宽既有防枚举归属口径。
 */
@RestController
@RequestMapping("/api/v1/subject/internal/subjects")
public class InternalAdmissionController {

    private final SubjectRegistrationService registrationService;

    public InternalAdmissionController(final SubjectRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /** 入驻状态（仅 subjectNo + status 两字段）。 */
    @GetMapping(path = "/{subjectNo}/admission", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.internal.read")
    public ApiResult<AdmissionView> admission(@PathVariable final String subjectNo) {
        return ApiResult.ok(new AdmissionView(subjectNo, registrationService.admission(subjectNo).name()));
    }

    /** 入驻状态视图（内部面最小暴露：无注册信息、无脱敏字段）。 */
    public record AdmissionView(String subjectNo, String status) {
    }
}