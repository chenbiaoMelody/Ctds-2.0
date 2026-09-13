package com.ctds.kms.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.kms.application.KeyManagementService;
import com.ctds.kms.interfaces.dto.KeyView;
import com.ctds.kms.interfaces.dto.RotationView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密钥管理端点（管理面，WBS-2.6.3 hifi §3.3）：创建/轮换须 admin 角色（kms.admin 权限注解强制，
 * 未认证 401 / 无权限 403+DENIED 审计——规格行为 2 GTT-3）。操作者取 AuthContext 当前身份，不收请求体传入。
 */
@RestController
@RequestMapping("/api/v1/keys")
public class KeyAdminController {

    private final KeyManagementService keyManagementService;

    public KeyAdminController(final KeyManagementService keyManagementService) {
        this.keyManagementService = keyManagementService;
    }

    /** 创建密钥（生成 16 字节随机材料为版本 1）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("kms.admin")
    public ApiResult<KeyView> create(@RequestBody final CreateKeyRequest request) {
        return ApiResult.ok(KeyView.from(keyManagementService.create(request.keyRef())));
    }

    /** 轮换密钥：新增版本，旧版本保留（旧密文不失效）。 */
    @PostMapping(path = "/{keyRef}/rotations", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("kms.admin")
    public ApiResult<RotationView> rotate(@PathVariable final String keyRef) {
        final var before = keyManagementService.descriptor(keyRef);
        final var after = keyManagementService.rotate(keyRef);
        return ApiResult.ok(new RotationView(keyRef, before.currentVersion(), after.currentVersion()));
    }

    /** 创建请求体（业务可读字段；keyRef 规则由应用服务校验并收敛 1002C0001）。 */
    public record CreateKeyRequest(String keyRef) {
    }
}
