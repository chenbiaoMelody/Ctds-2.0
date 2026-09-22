package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.did.application.DidIssuanceService;
import com.ctds.did.interfaces.dto.IssuanceView;
import com.ctds.did.interfaces.dto.RevocationView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DID 签发/吊销端点（WBS-3.1.8 hifi §4.1）：签发为内部触发面（回环边界、不设 JWT，登记诚实边界）；
 * 重试/重签/吊销为管理面（did.admin 权限强制）。
 */
@RestController
@RequestMapping("/api/v1/did")
public class DidIssuanceController {

    private final DidIssuanceService service;

    public DidIssuanceController(final DidIssuanceService service) {
        this.service = service;
    }

    /** 自动签发（幂等；KMS 失败返回 PENDING_ISSUE 业务答复，不抛 5xx）。 */
    @PostMapping(path = "/issuances", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<IssuanceView> issue(@RequestBody final IssueRequest request) {
        return ApiResult.ok(IssuanceView.from(service.issue(request.subjectNo())));
    }

    /** 运营重试（did.admin）。 */
    @PostMapping(path = "/subjects/{subjectNo}/issuance-retries",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<IssuanceView> retry(@PathVariable final String subjectNo) {
        return ApiResult.ok(IssuanceView.from(service.retry(subjectNo)));
    }

    /** 运营重签（did.admin）。 */
    @PostMapping(path = "/subjects/{subjectNo}/reissuances",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<IssuanceView> reissue(@PathVariable final String subjectNo) {
        return ApiResult.ok(IssuanceView.from(service.reissue(subjectNo)));
    }

    /** 吊销（did.admin）：理由必填、即时生效、不可逆。 */
    @PostMapping(path = "/{did}/revocation", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<RevocationView> revoke(@PathVariable final String did,
            @RequestBody final RevokeRequest request) {
        return ApiResult.ok(RevocationView.from(service.revoke(did, request.reason())));
    }

    /** 签发请求体（subjectName/subjectType 为契约可选字段，本包不落库）。 */
    public record IssueRequest(String subjectNo, String subjectName, String subjectType) {
    }

    /** 吊销请求体。 */
    public record RevokeRequest(String reason) {
    }
}
