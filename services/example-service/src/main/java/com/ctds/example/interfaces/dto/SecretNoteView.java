package com.ctds.example.interfaces.dto;

import java.util.UUID;

/**
 * 保密备注回读视图（演示）：GET 单条返回解密明文。
 */
public record SecretNoteView(UUID id, String text) {
}
