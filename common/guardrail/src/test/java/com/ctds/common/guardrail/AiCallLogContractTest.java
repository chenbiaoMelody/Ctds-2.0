package com.ctds.common.guardrail;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.guardrail.audit.AiCallLog;
import com.ctds.common.guardrail.audit.AiCallLogRecorder;
import com.ctds.common.guardrail.audit.AiCallOutcome;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCallLogContractTest {

    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "traceId", "feature", "actor", "provider", "model",
            "promptTokens", "completionTokens", "latencyMs",
            "outcome", "errorCode", "guardrailVerdict", "guardrailReason");

    @Test
    @DisplayName("T9 AiCallLog 契约：组件恰为 12 项且无内容字段")
    void recordShouldExposeExactlyMetadataComponents() {
        final RecordComponent[] components = AiCallLog.class.getRecordComponents();
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : components) {
            names.add(component.getName());
        }

        assertEquals(EXPECTED_COMPONENTS, names);
        final Set<String> forbidden = Set.of(
                "prompt", "messages", "message", "response", "content",
                "request", "payload", "secret", "password", "key");
        for (final String name : names) {
            assertFalse(forbidden.contains(name), "content field must not exist: " + name);
        }
    }

    @Test
    @DisplayName("T9 AiCallLog 归一：空白字符串归一、负数归 0、outcome 必填")
    void recordShouldNormalizeMetadata() {
        final AiCallLog log = new AiCallLog(
                null, " ", "", "\t", null,
                -1, -2, -3,
                AiCallOutcome.SUCCESS, "  ", null, " ");

        assertEquals("-", log.traceId());
        assertEquals("-", log.feature());
        assertEquals("-", log.actor());
        assertEquals("-", log.provider());
        assertEquals("-", log.model());
        assertEquals(0, log.promptTokens());
        assertEquals(0, log.completionTokens());
        assertEquals(0, log.latencyMs());
        assertNull(log.errorCode());
        assertNull(log.guardrailVerdict());
        assertEquals("-", log.guardrailReason());
    }

    @Test
    void recordShouldRejectNullOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new AiCallLog(
                "trace", "feature", "actor", "provider", "model",
                0, 0, 0, null, null, null, null));
    }

    @Test
    void noopRecorderShouldAcceptEveryLogWithoutThrowing() {
        final AiCallLog log = new AiCallLog(
                "trace", "feature", "actor", "provider", "model",
                1, 2, 3, AiCallOutcome.SUCCESS, null, null, null);
        assertDoesNotThrow(() -> AiCallLogRecorder.NOOP.record(log));
    }
}