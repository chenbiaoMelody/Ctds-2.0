package com.ctds.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.errorcode.BizException;
import com.ctds.example.domain.Greeting;
import com.ctds.example.infrastructure.InMemoryGreetingRepository;
import org.junit.jupiter.api.Test;

class GreetingServiceTest {

    @Test
    void greetShouldSaveAndReturnDomainObject() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository());

        final Greeting result = service.greet("你好，C-TDS");

        assertNotNull(result.id());
        assertEquals("你好，C-TDS", result.message());
    }

    @Test
    void greetShouldRejectBlankMessageAsParamInvalid() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository());

        final BizException thrown = assertThrows(BizException.class, () -> service.greet("   "));

        assertEquals("1000C0001", thrown.getErrorCode().value());
        assertEquals("message must not be null or blank", thrown.getMessage());
    }

    @Test
    void greetShouldRejectNullMessage() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository());

        final BizException thrown = assertThrows(BizException.class, () -> service.greet(null));

        assertEquals("1000C0001", thrown.getErrorCode().value());
    }
}
