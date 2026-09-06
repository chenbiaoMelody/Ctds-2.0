package com.ctds.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ctds.common.api.ApiResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

/**
 * 评审②P1 修复的行为锁定：过滤器在请求后清除 errorCode/module，保留 traceId（ADR-005 §3 第 6 项）。
 */
class LogContextCleanupFilterTest {

    private final LogContextCleanupFilter filter = new LogContextCleanupFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void filterShouldClearOwnKeysAfterRequest() throws Exception {
        MDC.put(LogContext.ERROR_CODE_MDC_KEY, "1000C0001");
        MDC.put(LogContext.MODULE_MDC_KEY, "greeting");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(MDC.get(LogContext.ERROR_CODE_MDC_KEY));
        assertNull(MDC.get(LogContext.MODULE_MDC_KEY));
    }

    @Test
    void filterShouldKeepTraceIdUntouched() throws Exception {
        MDC.put(ApiResult.TRACE_MDC_KEY, "t-1");
        MDC.put(LogContext.ERROR_CODE_MDC_KEY, "1000C0001");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("t-1", MDC.get(ApiResult.TRACE_MDC_KEY));
        assertNull(MDC.get(LogContext.ERROR_CODE_MDC_KEY));
    }
}
