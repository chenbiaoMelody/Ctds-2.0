package com.ctds.evidence.application;

/**
 * 核验结论（hifi §1.2）：三态 PASS / MISMATCH / NOT_ON_CHAIN；
 * MISMATCH 时给出重算 / 账本 / 本地三方摘要定位（规格 B3-2）。
 */
public record EvidenceVerifyView(
        String conclusion,
        String evidenceNo,
        String businessNo,
        String status,
        String expectedDigest,
        String actualDigest,
        String localDigest,
        String chainTxId,
        String verifiedBy,
        String verifiedAt) {
}
