package com.ctds.example.interfaces.dto;

import java.util.UUID;

/**
 * 问候响应视图。
 */
public record GreetingView(UUID id, String message) {
}
