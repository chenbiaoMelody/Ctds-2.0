package com.ctds.example.domain;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.UUID;

/**
 * 领域实体：问候。领域层是分层的中心，只依赖 JDK、common 公共组件与本包（ArchUnit 规则固化）。
 */
public record Greeting(UUID id, String message) {

    public Greeting {
        if (message == null || message.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "message must not be null or blank");
        }
    }

    public static Greeting of(final String message) {
        return new Greeting(UUID.randomUUID(), message);
    }
}
