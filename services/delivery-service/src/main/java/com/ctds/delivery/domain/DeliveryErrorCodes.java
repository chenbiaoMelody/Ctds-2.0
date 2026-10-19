package com.ctds.delivery.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 交付域错误码（1009 段顺延；1009C0001~0004 / 1009S0001 已由 WBS-3.5.2 网关占用）。
 * 文案均为服务端常量，禁止拼接用户输入；S 型文案由 {@link #MESSAGE_*_SYSTEM} 统一收敛。
 */
public final class DeliveryErrorCodes {

    /** 非合约需求方不可发起交付（与网关同值同义）。→ 403 */
    public static final ErrorCode REQUESTER_NOT_ALLOWED = ErrorCode.of("1009C0003");
    public static final String REQUESTER_NOT_ALLOWED_MESSAGE = "非合约需求方不可发起交付调用";

    /** 交付请求参数非法（合约编号 / 类型 / 幂等键 / fileId 等）。→ 400 */
    public static final ErrorCode DELIVERY_PARAM_INVALID = ErrorCode.of("1009C0005");
    public static final String DELIVERY_PARAM_INVALID_MESSAGE = "交付请求参数不合法";

    /** 交付形态或范围与合约约定不符。→ 400 */
    public static final ErrorCode DELIVERY_SCOPE_MISMATCH = ErrorCode.of("1009C0006");
    public static final String DELIVERY_SCOPE_MISMATCH_MESSAGE = "交付形态或范围与合约约定不符";

    /** 交付记录不存在或无权查看（同码同文防枚举）。→ 404 */
    public static final ErrorCode DELIVERY_NOT_VISIBLE = ErrorCode.of("1009C0007");
    public static final String DELIVERY_NOT_VISIBLE_MESSAGE = "交付记录不存在或无权查看";

    /** 文件超出交付上限（64MiB）。→ 400 */
    public static final ErrorCode FILE_TOO_LARGE = ErrorCode.of("1009C0008");
    public static final String FILE_TOO_LARGE_MESSAGE = "文件超出交付上限";

    /** 仅合约需求方可领取交付包。→ 403 */
    public static final ErrorCode PACKAGE_NOT_ALLOWED = ErrorCode.of("1009C0009");
    public static final String PACKAGE_NOT_ALLOWED_MESSAGE = "仅合约需求方可领取交付包";

    /** 交付依赖不可用（合约 / 目录 / 文件源未配置或丢失）→ 503。 */
    public static final ErrorCode DELIVERY_DEPENDENCY_UNAVAILABLE = ErrorCode.of("1009S0002");
    public static final String DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE = "交付依赖服务暂不可用";

    /** 交付包生成或读取失败 → 500。 */
    public static final ErrorCode PACKAGE_OPERATION_FAILED = ErrorCode.of("1009S0003");
    public static final String PACKAGE_OPERATION_FAILED_MESSAGE = "交付包生成或读取失败";

    private DeliveryErrorCodes() {
    }
}
