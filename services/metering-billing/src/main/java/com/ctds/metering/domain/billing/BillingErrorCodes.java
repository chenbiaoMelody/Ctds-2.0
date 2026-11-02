package com.ctds.metering.domain.billing;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 计费域错误码表（1011 段续用，hifi §7）：C0001~C0008 已由订单/评价/计量域占用，
 * 本域取 C0009~C0015；参数通用码沿 common（1000C0001），权限 403/401 沿 common-auth。
 * 对外文案全部为业务可读常量，不含内部实现细节。
 */
public final class BillingErrorCodes {

    /** 账单或对账数据不存在 / 非本方（404 同码同文防枚举，B12-3）。 */
    public static final ErrorCode BILL_NOT_VISIBLE = ErrorCode.of("1011C0009");
    public static final String BILL_NOT_VISIBLE_MESSAGE = "账单或对账数据不存在或不可读";

    /** 同合约同周期账单已存在（409 幂等负向，B10-1/ADR-007）。 */
    public static final ErrorCode BILL_DUPLICATED = ErrorCode.of("1011C0010");
    public static final String BILL_DUPLICATED_MESSAGE = "该合约该周期账单已存在，请勿重复出账";

    /** 账单已冻结（409 fail-closed，B11-3）。 */
    public static final ErrorCode BILL_FROZEN = ErrorCode.of("1011C0011");
    public static final String BILL_FROZEN_MESSAGE = "账单已冻结，当前操作不可执行";

    /** 账单当前状态不允许该操作（409）。 */
    public static final ErrorCode BILL_STATUS_CONFLICT = ErrorCode.of("1011C0012");
    public static final String BILL_STATUS_CONFLICT_MESSAGE = "账单当前状态不允许该操作";

    /** 交易额分成缺少 / 非法约定交易额（400，Q3-A）。 */
    public static final ErrorCode BILL_SHARE_BASE_INVALID = ErrorCode.of("1011C0013");
    public static final String BILL_SHARE_BASE_INVALID_MESSAGE = "交易额分成出账须提供有效的约定交易额";

    /** 合约不存在或不可读（404）。 */
    public static final ErrorCode CONTRACT_NOT_FOUND = ErrorCode.of("1011C0014");
    public static final String CONTRACT_NOT_FOUND_MESSAGE = "合约不存在或不可读";

    /** 合约不满足出账条件（免费档 / 未生效 / 周期无计量 / 数据异常，409；B8-2）。 */
    public static final ErrorCode CONTRACT_NOT_BILLABLE = ErrorCode.of("1011C0015");
    public static final String CONTRACT_NOT_BILLABLE_MESSAGE = "合约不满足出账条件，无法出账";

    private BillingErrorCodes() {
    }
}
