package com.ctds.gateway.error;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 网关自身错误码（1009 段 = 交付与沙箱域，3.5.1 Q8-A 预留、立卡实测全库零占用；hifi §7）。
 *
 * <p>纪律：网关只占用 1009 段自身错；未认证复用 {@code 1000C0002}、无权限复用 {@code 1000C0005}；
 * **越界一律透传合约域原码**（1008C0020 / 1008C0013 / 1008C0012 / 1008C0008），禁止改写与新增
 * "跳过判定 / 强制放行"入参。</p>
 */
public final class GatewayErrorCodes {

    /** 交付凭据缺失或格式非法（合约编号头）。→ 400 */
    public static final ErrorCode DELIVERY_CREDENTIAL_INVALID = ErrorCode.of("1009C0001");
    public static final String DELIVERY_CREDENTIAL_INVALID_MESSAGE = "交付凭据缺失或格式非法";

    /** 调用频率超出限制（网络级频率保护，非业务次数配额）。→ 429 */
    public static final ErrorCode RATE_LIMITED = ErrorCode.of("1009C0002");
    public static final String RATE_LIMITED_MESSAGE = "调用频率超出限制";

    /** 非合约需求方不可发起交付（发起人唯一）。→ 403 */
    public static final ErrorCode REQUESTER_NOT_ALLOWED = ErrorCode.of("1009C0003");
    public static final String REQUESTER_NOT_ALLOWED_MESSAGE = "非合约需求方不可发起交付调用";

    /** 请求路径不在已开放的交付面（白名单外，默认拒绝）。→ 404 */
    public static final ErrorCode DELIVERY_ROUTE_NOT_OPEN = ErrorCode.of("1009C0004");
    public static final String DELIVERY_ROUTE_NOT_OPEN_MESSAGE = "请求路径不在已开放的交付面";

    /** 策略判定服务不可达 / 超时（fail-closed，不放行）。→ 503 */
    public static final ErrorCode POLICY_SERVICE_UNAVAILABLE = ErrorCode.of("1009S0001");
    public static final String POLICY_SERVICE_UNAVAILABLE_MESSAGE = "策略判定服务暂不可用，请求未放行";

    private GatewayErrorCodes() {
    }
}
