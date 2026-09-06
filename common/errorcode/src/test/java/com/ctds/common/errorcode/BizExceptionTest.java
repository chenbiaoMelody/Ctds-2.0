package com.ctds.common.errorcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class BizExceptionTest {

    @Test
    void shouldCarryCodeAndMessage() {
        final BizException ex = new BizException(ErrorCodes.PARAM_INVALID, "参数不能为空");

        assertEquals("1000C0001", ex.getErrorCode().value());
        assertEquals("参数不能为空", ex.getMessage());
    }

    @Test
    void shouldKeepCause() {
        final IllegalStateException cause = new IllegalStateException("root");

        final BizException ex = new BizException(ErrorCodes.INTERNAL_ERROR, "wrapped", cause);

        assertSame(cause, ex.getCause());
    }
}
