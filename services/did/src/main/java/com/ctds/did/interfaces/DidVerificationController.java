package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.did.application.DidVerificationService;
import com.ctds.did.interfaces.dto.VerificationView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DID 验证端点（WBS-3.1.9 hifi §2）：三查判定（签名/状态/绑定），返回明确结论与失败原因；
 * 演示期无鉴权（回环网络隔离是真实边界，诚实边界登记 ADR-017 补记）。
 */
@RestController
@RequestMapping("/api/v1/did")
public class DidVerificationController {

    private final DidVerificationService service;

    public DidVerificationController(final DidVerificationService service) {
        this.service = service;
    }

    /** 验证（行为 3）：每次验证留痕；验证≠授权（结论仅代表身份主张成立）。 */
    @PostMapping(path = "/{did}/verifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<VerificationView> verify(@PathVariable final String did,
            @RequestBody final VerificationRequest request) {
        return ApiResult.ok(VerificationView.from(service.verify(did, request.data(), request.signature())));
    }

    /** 验证请求体（Base64：待验证数据 + SM2 DER 签名）。 */
    public record VerificationRequest(String data, String signature) {
    }
}