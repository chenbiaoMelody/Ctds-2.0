package com.ctds.kms.interfaces.dto;

/** 轮换结果视图（新旧版本；审计另落 kms_key_audit 表）。 */
public record RotationView(String keyRef, int oldVersion, int newVersion) {
}
