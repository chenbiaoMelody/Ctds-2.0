package com.ctds.subject.domain;

import java.time.LocalDateTime;

/**
 * 认证渠道调用记录（一行 = 一次渠道调用；渠道标识/请求流水号/结论三要素 + 耗时，规格行为 7 第 3 条）。
 * counted 仅对 LEGAL_PERSON 且 FAIL 为 1（计入当日核验失败次数）；渠道异常、OCR 调用与 GOV_CA
 * 政务验证一律 0（GOV_CA 无失败次数概念，WBS-3.1.4 lofi Q3-A）。
 */
public record CertVerificationLog(
        Long id,
        long subjectId,
        String verifyType,
        String channelCode,
        String channelRequestNo,
        String legalPersonName,
        String legalPersonIdCipher,
        VerificationConclusion conclusion,
        String failReason,
        int costMs,
        boolean counted,
        LocalDateTime createdAt) {

    /** 法人核验调用类型（规格行为 3）。 */
    public static final String TYPE_LEGAL_PERSON = "LEGAL_PERSON";
    /** 证照 OCR 调用类型（规格行为 7 第 3 条：任一次渠道调用全程留痕）。 */
    public static final String TYPE_OCR_LICENSE = "OCR_LICENSE";
    /** 政务 CA 证书验证调用类型（WBS-3.1.4，规格行为 6 第 2 条；counted 恒 0——无失败次数概念）。 */
    public static final String TYPE_GOV_CA = "GOV_CA";
}
