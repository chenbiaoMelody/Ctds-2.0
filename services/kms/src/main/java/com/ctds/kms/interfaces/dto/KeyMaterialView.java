package com.ctds.kms.interfaces.dto;

import com.ctds.kms.application.KeyManagementService;
import java.util.Base64;

/** 密钥材料视图（编号 + 版本 + Base64 材料；仅供给端点使用）。 */
public record KeyMaterialView(String keyRef, int version, String material) {

    public static KeyMaterialView from(final KeyManagementService.KeyMaterial material) {
        return new KeyMaterialView(material.keyRef(), material.version(),
                Base64.getEncoder().encodeToString(material.material()));
    }
}
