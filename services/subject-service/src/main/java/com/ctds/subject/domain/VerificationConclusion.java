package com.ctds.subject.domain;

/**
 * 认证渠道调用结论（规格行为 7 第 4 条：渠道异常与业务不通过严格区分——CHANNEL_ERROR 不计入核验失败次数）。
 */
public enum VerificationConclusion {

    /** 渠道返回通过。 */
    PASS,
    /** 渠道返回业务不通过（计入当日核验失败次数）。 */
    FAIL,
    /** 渠道技术异常（fail-fast，不计入失败次数）。 */
    CHANNEL_ERROR,
    /** 证照影像不可识别（OCR 调用；提示重传，不产生部分识别结果）。 */
    UNRECOGNIZABLE
}
