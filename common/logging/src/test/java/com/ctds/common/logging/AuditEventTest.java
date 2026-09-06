package com.ctds.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B3：AuditEvent 值对象——缺省补齐、防御性拷贝、必填校验（ADR-005 §3 第 6 项）。
 */
class AuditEventTest {

    @Test
    void ofShouldDefaultActorToAnonymous() {
        final AuditEvent event = AuditEvent.of(null, "greeting.create", "greeting", "g-1",
                AuditOutcome.SUCCESS, null);
        assertEquals("anonymous", event.actor());
    }

    @Test
    void ofShouldKeepGivenActor() {
        final AuditEvent event = AuditEvent.of("alice", "greeting.create", "greeting", "g-1",
                AuditOutcome.SUCCESS, null);
        assertEquals("alice", event.actor());
    }

    @Test
    void ofShouldCopyDetailDefensively() {
        final Map<String, String> mutable = new HashMap<>();
        mutable.put("channel", "web");
        final AuditEvent event = AuditEvent.of("alice", "greeting.create", "greeting", "g-1",
                AuditOutcome.SUCCESS, mutable);
        mutable.put("injected", "later");
        assertEquals("web", event.detail().get("channel"));
        assertFalse(event.detail().containsKey("injected"));
        assertThrows(UnsupportedOperationException.class, () -> event.detail().put("z", "1"));
    }

    @Test
    void ofShouldTreatNullDetailAsEmpty() {
        final AuditEvent event = AuditEvent.of("alice", "greeting.create", "greeting", "g-1",
                AuditOutcome.SUCCESS, null);
        assertTrue(event.detail().isEmpty());
    }

    @Test
    void ofShouldRejectBlankAction() {
        final BizException ex = assertThrows(BizException.class,
                () -> AuditEvent.of("alice", "   ", "greeting", "g-1", AuditOutcome.SUCCESS, null));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }

    @Test
    void ofShouldRejectNullAction() {
        assertThrows(BizException.class,
                () -> AuditEvent.of("alice", null, "greeting", "g-1", AuditOutcome.SUCCESS, null));
    }

    @Test
    void ofShouldRejectNullOutcome() {
        assertThrows(BizException.class,
                () -> AuditEvent.of("alice", "greeting.create", "greeting", "g-1", null, null));
    }
}
