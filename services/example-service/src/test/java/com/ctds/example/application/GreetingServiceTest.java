package com.ctds.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.example.domain.Greeting;
import com.ctds.example.infrastructure.InMemoryGreetingRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreetingServiceTest {

    @Test
    void greetShouldSaveAndReturnDomainObject() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });

        final Greeting result = service.greet("你好，C-TDS");

        assertNotNull(result.id());
        assertEquals("你好，C-TDS", result.message());
    }

    @Test
    void greetShouldRejectBlankMessageAsParamInvalid() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });

        final BizException thrown = assertThrows(BizException.class, () -> service.greet("   "));

        assertEquals("1000C0001", thrown.getErrorCode().value());
        assertEquals("message must not be null or blank", thrown.getMessage());
    }

    @Test
    void greetShouldRejectNullMessage() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });

        final BizException thrown = assertThrows(BizException.class, () -> service.greet(null));

        assertEquals("1000C0001", thrown.getErrorCode().value());
    }

    @Test
    void deleteShouldRemoveAndRecordSuccessAudit() {
        final List<AuditEvent> events = new ArrayList<>();
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), events::add);
        final Greeting created = service.greet("bye");

        service.delete(created.id());

        assertEquals(0, service.list(com.ctds.common.pagination.PageQuery.of(1, 10, null)).list().size());
        final AuditEvent event = events.get(events.size() - 1);
        assertEquals("greeting.delete", event.action());
        assertEquals(AuditOutcome.SUCCESS, event.outcome());
        // 本层无请求上下文时 actor 由 AuditEvent 缺省为 anonymous；真实身份断言见 AuthDemoIntegrationTest
        assertEquals("anonymous", event.actor());
    }

    @Test
    void deleteUnknownIdShouldReturnResourceNotFound() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository(), event -> { });

        final BizException thrown = assertThrows(BizException.class,
                () -> service.delete(java.util.UUID.randomUUID()));

        assertEquals("1000C0003", thrown.getErrorCode().value());
    }
}
