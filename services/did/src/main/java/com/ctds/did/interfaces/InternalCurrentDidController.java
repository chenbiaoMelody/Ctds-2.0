package com.ctds.did.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.did.application.DidCurrentQueryService;
import com.ctds.did.application.DidCurrentQueryService.CurrentDidView;
import com.ctds.did.domain.DidErrorCodes;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部只读端点（WBS-3.6.3 hifi §8-②，ADR-017 补记）：
 * 供连接器注册核验按主体编号取"当前 DID + 状态"（核验第二查与绑定实时联动）。
 * 功能门槛 = 服务身份专用权限点 {@code did.internal.read}（仅 connector-internal 持有，不外借 did.admin）。
 * 最小暴露：subjectNo / did / status（NONE = 该主体尚无 DID 记录，业务状态而非错误）。
 */
@RestController
@RequestMapping("/api/v1/did/internal/subjects")
public class InternalCurrentDidController {

    private static final int SUBJECT_NO_MAX_LENGTH = 32;

    private final DidCurrentQueryService queryService;

    public InternalCurrentDidController(final DidCurrentQueryService queryService) {
        this.queryService = queryService;
    }

    /** 当前 DID（ACTIVE / REVOKED / PENDING_ISSUE / NONE；无记录不报错）。 */
    @GetMapping(path = "/{subjectNo}/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("did.internal.read")
    public ApiResult<CurrentDidView> current(@PathVariable final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank() || subjectNo.length() > SUBJECT_NO_MAX_LENGTH) {
            throw new BizException(DidErrorCodes.DID_PARAM_INVALID, "主体编号不合法");
        }
        return ApiResult.ok(queryService.current(subjectNo));
    }
}
