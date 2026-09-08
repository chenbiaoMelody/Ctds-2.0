package com.ctds.example.interfaces.dto;

/**
 * 保密备注请求体（演示）：明文只在请求瞬间出现，服务端加密后存储。
 */
public record SecretNoteRequest(String text) {
}
