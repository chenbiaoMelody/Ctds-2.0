package com.ctds.example.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.UUID;

/**
 * 领域实体：保密备注——存储的是 SM4 密文信封（Base64），明文只在应用服务加解密时短暂存在。
 * 国密组件演示（WBS 2.4.6 B11）：存密文、取明文、篡改被拒。
 */
public record SecretNote(UUID id, String envelope) {

    public SecretNote {
        if (envelope == null || envelope.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "envelope must not be null or blank");
        }
    }

    public static SecretNote of(final String envelope) {
        return new SecretNote(UUID.randomUUID(), envelope);
    }
}
