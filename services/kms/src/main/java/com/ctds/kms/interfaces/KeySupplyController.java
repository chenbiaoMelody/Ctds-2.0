package com.ctds.kms.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.kms.application.KeyManagementService;
import com.ctds.kms.interfaces.dto.KeyMaterialView;
import com.ctds.kms.interfaces.dto.KeyView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密钥供给端点（读取面，供 common-crypto KmsKeyProvider 服务间调用，WBS-2.6.3 hifi §3.3）。
 * 诚实边界（交付说明已登记）：本组端点面向内部网络边界，V1.0 不设 JWT——材料明文传输属 G-01 既有缺口，
 * 不虚报已加密；管理面写操作另经 KeyAdminController 的 RBAC 强制。
 */
@RestController
@RequestMapping("/api/v1/keys")
public class KeySupplyController {

    private final KeyManagementService keyManagementService;

    public KeySupplyController(final KeyManagementService keyManagementService) {
        this.keyManagementService = keyManagementService;
    }

    /** 密钥元数据（当前版本/状态，无材料）。 */
    @GetMapping(path = "/{keyRef}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KeyView> descriptor(@PathVariable final String keyRef) {
        return ApiResult.ok(KeyView.from(keyManagementService.descriptor(keyRef)));
    }

    /** 当前版本材料（Base64 编码 SM4 密钥 16 字节）。 */
    @GetMapping(path = "/{keyRef}/material", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KeyMaterialView> currentMaterial(@PathVariable final String keyRef) {
        return ApiResult.ok(KeyMaterialView.from(keyManagementService.currentMaterial(keyRef)));
    }

    /** 历史版本材料（按密文信封内版本号取用，轮换后旧密文解密路径）。 */
    @GetMapping(path = "/{keyRef}/versions/{version}/material", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<KeyMaterialView> versionMaterial(@PathVariable final String keyRef,
                                                      @PathVariable final int version) {
        return ApiResult.ok(KeyMaterialView.from(keyManagementService.material(keyRef, version)));
    }
}
