package com.ctds.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ctds.common.api.ApiResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * B2：LogContext 行为——错误码/模块进 MDC、空值清除、clear 不动 traceId（ADR-005 §3 第 6 项）。
 */
class LogContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void setErrorCodeShouldPutMdcValue() {
        LogContext.setErrorCode("1000C0001");
        assertEquals("1000C0001", MDC.get(LogContext.ERROR_CODE_MDC_KEY));
    }

    @Test
    void setErrorCodeWithEmptyValueShouldRemoveKey() {
        LogContext.setErrorCode("1000C0001");
        LogContext.setErrorCode("");
        assertNull(MDC.get(LogContext.ERROR_CODE_MDC_KEY));
    }

    @Test
    void setErrorCodeWithNullShouldRemoveKey() {
        LogContext.setErrorCode("1000C0001");
        LogContext.setErrorCode(null);
        assertNull(MDC.get(LogContext.ERROR_CODE_MDC_KEY));
    }

    @Test
    void setModuleShouldPutMdcValue() {
        LogContext.setModule("greeting");
        assertEquals("greeting", MDC.get(LogContext.MODULE_MDC_KEY));
    }

    @Test
    void setModuleWithEmptyValueShouldRemoveKey() {
        LogContext.setModule("greeting");
        LogContext.setModule("");
        assertNull(MDC.get(LogContext.MODULE_MDC_KEY));
    }

    @Test
    void setModuleWithNullShouldRemoveKey() {
        LogContext.setModule("greeting");
        LogContext.setModule(null);
        assertNull(MDC.get(LogContext.MODULE_MDC_KEY));
    }

    @Test
    void clearShouldRemoveOwnKeysButKeepTraceId() {
        MDC.put(ApiResult.TRACE_MDC_KEY, "t-1");
        LogContext.setErrorCode("1000C0001");
        LogContext.setModule("greeting");
        LogContext.clear();
        assertEquals("t-1", MDC.get(ApiResult.TRACE_MDC_KEY));
        assertNull(MDC.get(LogContext.ERROR_CODE_MDC_KEY));
        assertNull(MDC.get(LogContext.MODULE_MDC_KEY));
    }
}
