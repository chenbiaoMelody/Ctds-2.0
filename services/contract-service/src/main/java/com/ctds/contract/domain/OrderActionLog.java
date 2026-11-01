package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 订单统一留痕行（对应 product_order_action_log 表，WBS-3.7.3 hifi §2.2）：四要素（谁/何时/
 * 哪个订单/动作）+ from→to 状态 + 业务文案。只插不改；reason 仅业务常量文案（关闭原因 /
 * 拒绝说明），零条款值/合同正文（B4-3；数据分级 CAT-06 L3 审计留痕）。
 */
public record OrderActionLog(Long id, String orderNo, OrderAction action, String fromStatus,
        String toStatus, String reason, String operatorSubjectNo, LocalDateTime createdAt) {
}
