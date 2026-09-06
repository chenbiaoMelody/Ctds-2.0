package com.ctds.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void greetShouldRejectBlankMessage() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository());

        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.greet("   "));

        assertEquals("message must not be blank", thrown.getMessage());
    }

    @Test
    void greetShouldRejectNullMessage() {
        final GreetingService service = new GreetingService(new InMemoryGreetingRepository());

        assertThrows(NullPointerException.class, () -> service.greet(null));
    }
}
