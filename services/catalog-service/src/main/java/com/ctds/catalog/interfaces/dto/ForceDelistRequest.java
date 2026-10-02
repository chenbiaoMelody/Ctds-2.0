package com.ctds.catalog.interfaces.dto;

/**
 * 强制下架请求体（WBS-3.3.5 hifi §1 W12 治理兜底）：理由必填（1~256 字），留痕 summary 恒含
 * 理由全文与操作者（行为 4 规则 4 判定面——"留痕含理由与操作者"）。
 */
public record ForceDelistRequest(String forceReason) {
}
