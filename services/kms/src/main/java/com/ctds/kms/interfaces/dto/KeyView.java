package com.ctds.kms.interfaces.dto;

import com.ctds.kms.domain.KeyDescriptor;
import java.time.LocalDateTime;

/** 密钥元数据视图（无材料；材料只经供给端点出站）。 */
public record KeyView(String keyRef, String status, int currentVersion, LocalDateTime createdAt) {

    public static KeyView from(final KeyDescriptor descriptor) {
        return new KeyView(descriptor.keyRef(), descriptor.status().name(),
                descriptor.currentVersion(), descriptor.createdAt());
    }
}
