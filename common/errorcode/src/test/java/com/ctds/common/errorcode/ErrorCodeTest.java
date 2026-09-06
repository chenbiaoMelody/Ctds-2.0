package com.ctds.common.errorcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void ofShouldAcceptWellFormedCode() {
        final ErrorCode code = ErrorCode.of("1001C0001");

        assertEquals("1001C0001", code.value());
        assertEquals(ErrorType.CLIENT, code.type());
    }

    @Test
    void ofShouldMapAllTypeMarkers() {
        assertEquals(ErrorType.CLIENT, ErrorCode.of("1000C0001").type());
        assertEquals(ErrorType.BUSINESS, ErrorCode.of("1000B0001").type());
        assertEquals(ErrorType.SYSTEM, ErrorCode.of("1000S0001").type());
    }

    @Test
    void ofShouldRejectBadFormats() {
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of(null));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of(""));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("0"));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("100C0001"));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("1000X0001"));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("AB01C0001"));
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.of("1000C000A"));
    }
}
