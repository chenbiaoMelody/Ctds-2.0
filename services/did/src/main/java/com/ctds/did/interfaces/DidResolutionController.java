package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.did.application.DidResolutionService;
import com.ctds.did.interfaces.dto.ResolutionView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DID 解析端点（WBS-3.1.9 hifi §2）：只读公开要素 + 状态（有效/已吊销）；
 * 演示期无鉴权（回环网络隔离是真实边界，诚实边界登记 ADR-017 补记）。
 */
@RestController
@RequestMapping("/api/v1/did")
public class DidResolutionController {

    private final DidResolutionService service;

    public DidResolutionController(final DidResolutionService service) {
        this.service = service;
    }

    /** 解析（行为 2）：未登记 → 1005B0003 明确业务答复。 */
    @GetMapping(path = "/{did}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ResolutionView> resolve(@PathVariable final String did) {
        return ApiResult.ok(ResolutionView.from(service.resolve(did)));
    }
}