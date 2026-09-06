package com.ctds.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiResultTest {

    @Test
    void okShouldUseZeroCodeAndSanitizedTraceId() {
        final ApiResult<String> result = ApiResult.ok("payload", "t-1");

        assertEquals("0", result.code());
        assertEquals("success", result.message());
        assertEquals("t-1", result.traceId());
        assertEquals("payload", result.data());
    }

    @Test
    void sanitizeShouldRejectIllegalAndOverlongValues() {
        assertEquals("-", ApiResult.sanitizeTraceId(null));
        assertEquals("-", ApiResult.sanitizeTraceId(""));
        assertEquals("-", ApiResult.sanitizeTraceId("<script>alert(1)</script>"));
        assertEquals("-", ApiResult.sanitizeTraceId("a".repeat(65)));
        assertEquals("-", ApiResult.sanitizeTraceId("包含中文"));
    }

    @Test
    void sanitizeShouldKeepLegalValues() {
        assertEquals("t-123", ApiResult.sanitizeTraceId("t-123"));
        assertEquals("a-b_c.1", ApiResult.sanitizeTraceId("a-b_c.1"));
        assertEquals("x".repeat(64), ApiResult.sanitizeTraceId("x".repeat(64)));
    }
}
