package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.did.application.DidDemoSignatureService;
import com.ctds.did.interfaces.dto.DemoSignatureView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示签名入口端点（规格 C-1.2 §6 第 6 条「平台侧代签边界」，WBS-3.1.11 hifi §2.1）：
 * 仅演示/调试期可用——生产以配置门槛关闭（默认关闭 → 1000C0003），且须 `did.admin`。
 * 签名一律经 KMS 内部签名面，私钥不出 KMS。
 */
@RestController
@RequestMapping("/api/v1/did")
public class DidDemoSignatureController {

    private final DidDemoSignatureService service;

    public DidDemoSignatureController(final DidDemoSignatureService service) {
        this.service = service;
    }

    /** 代签（演示/调试期）：{@code {data}} 为待签原文（1~1024 字符），返回原文与 SM2 DER 签名的 Base64。 */
    @PostMapping(path = "/{did}/demo-signatures", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.admin")
    public ApiResult<DemoSignatureView> sign(@PathVariable final String did,
            @RequestBody final DemoSignatureRequest request) {
        return ApiResult.ok(DemoSignatureView.from(service.sign(did, request.data())));
    }

    /** 演示代签请求体（data = 待签原文）。 */
    public record DemoSignatureRequest(String data) {
    }
}
