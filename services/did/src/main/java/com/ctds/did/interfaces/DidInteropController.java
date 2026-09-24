package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.did.application.DidInteropService;
import com.ctds.did.interfaces.dto.InteropSampleView;
import com.ctds.did.interfaces.dto.InteropVerificationView;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨空间身份互认端点（WBS-3.1.10 hifi §2）：来访验证 / 出向验证 / 样例清单。
 * <p>演示期无鉴权（对外验证能力；回环网络隔离是真实边界，诚实边界登记 ADR-017 补记）。</p>
 */
@RestController
@RequestMapping("/api/v1/did-interop")
public class DidInteropController {

    private final DidInteropService service;

    public DidInteropController(final DidInteropService service) {
        this.service = service;
    }

    /** 来访验证（行为 5 规则 1/2）：每次业务结论留痕（对端空间标识 / DID / 时间 / 结果）。 */
    @PostMapping(path = "/inbound-verifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<InteropVerificationView> verifyInbound(@RequestBody final InboundVerificationRequest request) {
        return ApiResult.ok(InteropVerificationView.from(
                service.verifyInbound(request.peerSpace(), request.did(), request.data(), request.signature())));
    }

    /** 出向验证（行为 5 验收标准 3 双向口径）：验证本空间 DID 在对端口径下的结论。 */
    @PostMapping(path = "/outbound-verifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<InteropVerificationView> verifyOutbound(@RequestBody final OutboundVerificationRequest request) {
        return ApiResult.ok(InteropVerificationView.from(service.verifyOutbound(request.did())));
    }

    /** 预置样例清单（只读；剧本附录 A 组 E1 取用口径）。 */
    @GetMapping(path = "/samples", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<InteropSampleView>> samples() {
        return ApiResult.ok(InteropSampleView.from(service.samples()));
    }

    /** 来访验证请求体（Base64：原文 + SM2 DER 签名）。 */
    public record InboundVerificationRequest(String peerSpace, String did, String data, String signature) {
    }

    /** 出向验证请求体（本空间 DID）。 */
    public record OutboundVerificationRequest(String did) {
    }
}
