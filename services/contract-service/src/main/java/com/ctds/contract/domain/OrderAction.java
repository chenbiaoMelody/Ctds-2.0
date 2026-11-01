package com.ctds.contract.domain;

/**
 * 订单留痕动作四码（WBS-3.7.3 hifi §2.2；B4-3 全生命周期留痕 + B4-4 拒绝留痕）。
 * 只插不改；DENIED_ACCESS 为越权访问拒绝行，用户读面不返回（hifi §1.4 注——拒绝留痕
 * 仅供治理与审计），reason 仅业务常量文案零敏感原文。
 */
public enum OrderAction {
    /** 创建订单（订单登记 + 合约草案同事务落库）。 */
    CREATE,
    /** 合约生效，订单完成（B3-4 联动一：IN_PROGRESS → COMPLETED）。 */
    COMPLETE,
    /** 合约终止，订单关闭（B3-4 联动二：IN_PROGRESS → CLOSED）。 */
    CLOSE,
    /** 非本方访问订单详情被拒（防枚举同形 1011C0002 联动留痕）。 */
    DENIED_ACCESS
}
