package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.evidence.application.EvidenceCreateView;
import com.ctds.evidence.application.EvidenceWriteService;
import com.ctds.evidence.interfaces.dto.CreateEvidenceRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部写面（hifi §1.1，不经统一网关）：权限点 {@code evidence.internal.write} 仅业务系统
 * 服务身份持有（3.8.3 四环节埋点使用）；**恒 200**——上链失败以 {@code status=FAILED} 体现，
 * 业务侧不因存证链故障回滚（规格 B2-1 fail-visible）。
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceInternalController {

    private final EvidenceWriteService writeService;

    public EvidenceInternalController(final EvidenceWriteService writeService) {
        this.writeService = writeService;
    }

    @PostMapping(path = "/internal/records", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("evidence.internal.write")
    public ApiResult<EvidenceCreateView> create(@RequestBody final CreateEvidenceRequest request) {
        return ApiResult.ok(writeService.create(request.toCommand()));
    }
}
