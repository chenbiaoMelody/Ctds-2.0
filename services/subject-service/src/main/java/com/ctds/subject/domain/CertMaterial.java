package com.ctds.subject.domain;

import java.time.LocalDateTime;

/**
 * 证照材料（一行 = 一张影像及 OCR 结果；重复上传替换保留最近一次——hifi 库表设计契约，
 * (subject_id, material_type) 唯一键兜底并发替换窗口）。
 * L4 边界：contentCipher（影像）与 ocrRawCipher（OCR 原始结果）为 SM4 密文；
 * contentSm3 为影像 SM3 完整性摘要（唯一入口 common-crypto，ADR-006 Q2 裁决）；
 * ocrUscc / ocrLegalPerson 为比对锚点明文（组织信息/姓名，非证件号码，hifi 库表设计节）。
 */
public record CertMaterial(
        Long id,
        long subjectId,
        String materialType,
        String fileName,
        String contentSm3,
        byte[] contentCipher,
        byte[] ocrRawCipher,
        String ocrUscc,
        String ocrLegalPerson,
        boolean ocrRecognizable,
        String confirmedName,
        String confirmedUscc,
        String confirmedLegalPerson,
        String confirmedRegAddress,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt) {

    /** 营业执照材料类型（规格行为 2；政务 CA 材料随 3.1.4 另立类型）。 */
    public static final String TYPE_BUSINESS_LICENSE = "BUSINESS_LICENSE";

    /** 申请人是否已完成核对确认。 */
    public boolean confirmed() {
        return confirmedAt != null;
    }
}
