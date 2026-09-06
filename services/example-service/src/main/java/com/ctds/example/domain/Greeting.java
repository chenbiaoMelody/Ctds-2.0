package com.ctds.example.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 领域实体：问候。领域层是分层的中心，只依赖 JDK 与本包（ArchUnit 规则固化）。
 */
public record Greeting(UUID id, String message) {

    public Greeting {
        Objects.requireNonNull(message, "message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public static Greeting of(final String message) {
        return new Greeting(UUID.randomUUID(), message);
    }
}
