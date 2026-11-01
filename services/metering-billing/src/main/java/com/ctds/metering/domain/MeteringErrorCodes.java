package com.ctds.metering.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 计量域错误码表（1011 段续用，规格 C-7.1-7.2 §6-3 预留；C0001~C0007 已由订单域/评价域占用，
 * C0008 起本域——前置检查实测 Java/docs 双侧空位）。参数通用码沿 common（1000C0001）。
 */
public final class MeteringErrorCodes {

    /** 计量数据不存在或非本方（404 同形防枚举，C0002/C0007 同口径；hifi §4）。 */
    public static final ErrorCode METERING_NOT_VISIBLE = ErrorCode.of("1011C0008");
    public static final String METERING_NOT_VISIBLE_MESSAGE = "计量数据不存在或不可读";

    private MeteringErrorCodes() {
    }
}
