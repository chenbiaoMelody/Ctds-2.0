package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.subject.application.CertChannelUnavailableException;
import com.ctds.subject.application.CertificationActionResult;
import com.ctds.subject.application.CertificationProfile;
import com.ctds.subject.application.CertificationService;
import com.ctds.subject.application.ConfirmationCommand;
import com.ctds.subject.application.ConfirmationResult;
import com.ctds.subject.application.ImageView;
import com.ctds.subject.application.LicenseUploadResult;
import com.ctds.subject.application.VerificationCommand;
import com.ctds.subject.application.VerificationResult;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.interfaces.dto.ConfirmationRequest;
import com.ctds.subject.interfaces.dto.VerificationRequest;
import jakarta.validation.Valid;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

/**
 * 实名认证端点（WBS-3.1.3 hifi 接口契约，规格 C-1.1 行为 2/3/4/7）：
 * 上传 OCR / 核对确认 / 法人核验 / 档案查询 / 影像查看 / 结束认证。
 * 全部端点经 OwnershipGuard 对象级归属断言（ADR-016 §2.6），权限注解强制
 * （subject.certify 申请人 / subject.read 查询；未认证 401 / 无权限 403）。
 */
@RestController
@RequestMapping("/api/v1/subject/registrations/{subjectNo}/certification")
public class CertificationController {

    private final CertificationService certificationService;

    public CertificationController(final CertificationService certificationService) {
        this.certificationService = certificationService;
    }

    /** 上传营业执照并触发 OCR 识别（影像与原始结果 L4 加密落库；识别要素回填供核对）。 */
    @PostMapping(path = "/license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.certify")
    public ApiResult<LicenseUploadResult> uploadLicense(@PathVariable final String subjectNo,
            @RequestParam("file") final MultipartFile file) throws IOException {
        return ApiResult.ok(certificationService.uploadLicense(subjectNo, file.getBytes(),
                file.getOriginalFilename()));
    }

    /** 核对确认回填要素（信用代码与识别值不一致阻断——规格行为 2 第 4 条）。 */
    @PostMapping(path = "/license/confirmation", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.certify")
    public ApiResult<ConfirmationResult> confirmLicense(@PathVariable final String subjectNo,
            @Valid @RequestBody final ConfirmationRequest request) {
        return ApiResult.ok(certificationService.confirmLicense(subjectNo,
                new ConfirmationCommand(request.subjectName(), request.uscc(), request.legalPerson(),
                        request.regAddress())));
    }

    /** 发起法人核验（不挂幂等：失败重试须真执行；防重复点击由当日上限与留痕兜底）。 */
    @PostMapping(path = "/legal-person-verifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.certify")
    public ApiResult<VerificationResult> verifyLegalPerson(@PathVariable final String subjectNo,
            @Valid @RequestBody final VerificationRequest request) {
        return ApiResult.ok(certificationService.verifyLegalPerson(subjectNo,
                new VerificationCommand(request.legalPersonName(), request.legalPersonIdNo())));
    }

    /** 认证进度档案（身份证号等 L4 字段不回显）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.read")
    public ApiResult<CertificationProfile> profile(@PathVariable final String subjectNo) {
        return ApiResult.ok(certificationService.profile(subjectNo));
    }

    /** 查看证照影像（解密返回；查看行为留审计——规格行为 2 验收第 4 条）。 */
    @GetMapping(path = "/license/image", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.read")
    public ApiResult<ImageView> viewImage(@PathVariable final String subjectNo) {
        return ApiResult.ok(certificationService.viewImage(subjectNo));
    }

    /** 结束认证（待认证 → 认证失败，lofi Q1-A 裁决口径；之后可重新发起核验）。 */
    @PostMapping(path = "/abandonment", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.certify")
    public ApiResult<CertificationActionResult> abandonCertification(@PathVariable final String subjectNo) {
        return ApiResult.ok(certificationService.abandonCertification(subjectNo));
    }

    /**
     * 渠道不可用精确出站（规格行为 3 第 5 条）：503 + 业务文案"认证服务暂不可用，请稍后重试"。
     * 本地处理器优先于全局 S 型脱敏（@Order 先于 common GlobalExceptionHandler，common 组件零改动）。
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RestControllerAdvice
    static class CertChannelExceptionHandler {

        @ExceptionHandler(CertChannelUnavailableException.class)
        public ResponseEntity<ApiResult<Void>> onChannelUnavailable(
                final CertChannelUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResult<>(SubjectErrorCodes.CERT_CHANNEL_UNAVAILABLE.value(), ex.getMessage(),
                            ApiResult.currentTraceId(), null));
        }
    }
}
