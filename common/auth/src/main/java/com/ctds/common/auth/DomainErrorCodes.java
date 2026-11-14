package com.ctds.common.auth;

import com.ctds.common.errorcode.ErrorCode;

/**
 * WBS-3.9.3 Q4-A：1013 段部署域语义新码（顺延 RbacErrorCodes 已占用的 1013C0001~0003/1013S0001）。
 * 常量落 common/auth：servlet/gateway 过滤器直接写响应体所需，evidence 复用同一类（同码同文）。
 */
public final class DomainErrorCodes {

    /** 部署域已退役：退役态业务请求被拒（503，B9-R6；豁免面见 DomainProperties 豁免模式）。 */
    public static final ErrorCode DOMAIN_RETIRED = ErrorCode.of("1013C0004");
    public static final String DOMAIN_RETIRED_MESSAGE = "部署域已退役";

    /** 部署域配置不一致：声明清单与实际不符（503，B9-3 fail-closed；正常路径由启动校验先行拦截）。 */
    public static final ErrorCode CONFIG_MISMATCH = ErrorCode.of("1013S0002");
    public static final String CONFIG_MISMATCH_MESSAGE = "部署域配置与实际不一致，请联系系统管理员";

    private DomainErrorCodes() {
    }
}
