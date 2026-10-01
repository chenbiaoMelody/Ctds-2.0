package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 产品变更留痕行（WBS-3.3.4 hifi §1 R8 出参行；载体 = product_action_log，写入归 3.3.5，
 * 本卡只读——按 created_at 倒序分页返回给本人订阅者）。
 *
 * @param action            动作码（值域随 3.3.5 封装写面登记）
 * @param summary           变更摘要（业务可读文本；不含敏感原文与数据本体）
 * @param operatorSubjectNo 操作者主体编号（四要素"谁"）
 * @param createdAt         发生时间（四要素"何时"）
 */
public record ProductChangeLogRow(String action, String summary, String operatorSubjectNo,
        LocalDateTime createdAt) {
}
