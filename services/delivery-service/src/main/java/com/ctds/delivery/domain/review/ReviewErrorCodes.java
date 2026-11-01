package com.ctds.delivery.domain.review;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 评价域错误码（1011 段续用，规格 §6-3 预留；C0001~C0004 已由 WBS-3.7.3 订单域占用）。
 * 文案均为服务端常量，禁止拼接用户输入。
 */
public final class ReviewErrorCodes {

    /** 评分值非法（1~5 整数之外）。→ 400 */
    public static final ErrorCode REVIEW_SCORE_INVALID = ErrorCode.of("1011C0005");
    public static final String REVIEW_SCORE_INVALID_MESSAGE = "评分需为 1~5 的整数";

    /** 无有效交付资格（防刷评；B5-1 资格 = 已完成有效交付的需方）。→ 403 */
    public static final ErrorCode REVIEW_ELIGIBILITY_DENIED = ErrorCode.of("1011C0006");
    public static final String REVIEW_ELIGIBILITY_DENIED_MESSAGE = "完成对该产品的有效交付后方可评价";

    /** 评价不存在或目标不存在（同码同文防枚举，沿 3.7.3 订单域 C0002 口径）。→ 404 */
    public static final ErrorCode REVIEW_NOT_FOUND = ErrorCode.of("1011C0007");
    public static final String REVIEW_NOT_FOUND_MESSAGE = "评价不存在或不可操作";

    private ReviewErrorCodes() {
    }
}
