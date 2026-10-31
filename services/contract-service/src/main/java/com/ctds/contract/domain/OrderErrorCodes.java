package com.ctds.contract.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 订单服务码表（模块位 11，1011 段定稿——规格 C-7.1 §6-3 预留段，WBS-3.7.3 hifi §1.5；
 * 编码段前置检查实测全库零占用后落码）。对外文案为服务端常量，禁止拼接用户输入（章程 4.3）；
 * 精确 HTTP 映射见 interfaces.OrderExceptionHandler；合约发起门槛链错误（1008 段）原样透传
 * 不包装（订单不复制合约域判定）。
 */
public final class OrderErrorCodes {

    /** 同需方同产品已有进行中订单（B3-2 订购幂等；预检查 + uk_active_order_key 唯一索引
     * 兜底并发同译本码——hifi §3.2）。→ 409 */
    public static final ErrorCode ORDER_DUPLICATED = ErrorCode.of("1011C0001");
    public static final String ORDER_DUPLICATED_MESSAGE = "该产品已有进行中订单，请勿重复订购";
    /** 订单不存在或非本方（B4-4 防枚举：不存在与越权同码同文——hifi §3.4；拒绝留痕联动）。→ 404 */
    public static final ErrorCode ORDER_NOT_ACCESSIBLE = ErrorCode.of("1011C0002");
    public static final String ORDER_NOT_ACCESSIBLE_MESSAGE = "订单不存在或无权查看";
    /** 请求参数不合法（requestId 缺失/空白/超长、非法 status/productId/分页——hifi §1.2）。→ 400 */
    public static final ErrorCode ORDER_PARAM_INVALID = ErrorCode.of("1011C0003");
    public static final String ORDER_PARAM_INVALID_MESSAGE = "请求参数不合法";
    /** 订单号并发冲突 / 其他并发写冲突（uk_order_no / uk_active_order_key 并发兜底转译，
     * 整个事务回滚不残留合约——hifi §3.2）。→ 409 */
    public static final ErrorCode ORDER_CONCURRENT_CONFLICT = ErrorCode.of("1011C0004");
    public static final String ORDER_CONCURRENT_CONFLICT_MESSAGE = "订单正在处理中，请重试";

    private OrderErrorCodes() {
    }
}
