package com.ctds.kms.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.application.KeyPairService;
import com.ctds.kms.domain.KmsErrorCodes;
import com.ctds.kms.interfaces.dto.KeyPairView;
import com.ctds.kms.interfaces.dto.SignatureView;
import java.util.Base64;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SM2 密钥对端点（WBS-3.1.8，hifi §4.2）：创建须 kms.admin（管理面）；签名面为内部回环边界、不设 JWT
 * （沿 KeySupplyController 诚实边界先例）。私钥不出 KMS：本组端点不返回任何私钥/材料字段。
 */
@RestController
@RequestMapping("/api/v1/key-pairs")
public class KeyPairController {

    private final KeyPairService keyPairService;

    public KeyPairController(final KeyPairService keyPairService) {
        this.keyPairService = keyPairService;
    }

    /** 生成 SM2 密钥对（私钥只存 KMS，仅返回公钥）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("kms.admin")
    public ApiResult<KeyPairView> create(@RequestBody final CreateKeyPairRequest request) {
        return ApiResult.ok(KeyPairView.from(keyPairService.create(request.keyRef())));
    }

    /** 内部签名（私钥不出 KMS；data 为 Base64 编码的待签内容，返回 SM2 DER 签名 Base64）。 */
    @PostMapping(path = "/{keyRef}/signatures", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SignatureView> sign(@PathVariable final String keyRef,
            @RequestBody final SignRequest request) {
        return ApiResult.ok(SignatureView.from(keyRef, keyPairService.sign(keyRef, decode(request.data()))));
    }

    private static byte[] decode(final String data) {
        if (data == null || data.isBlank()) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "待签名数据不能为空");
        }
        try {
            return Base64.getDecoder().decode(data);
        } catch (final IllegalArgumentException e) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "待签名数据不是合法 Base64");
        }
    }

    /** 创建请求体（业务可读字段；keyRef 规则由应用服务校验并收敛 1002C0001）。 */
    public record CreateKeyPairRequest(String keyRef) {
    }

    /** 签名请求体（data = Base64 待签内容）。 */
    public record SignRequest(String data) {
    }
}
