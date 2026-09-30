package com.ctds.catalog.interfaces.dto;

/**
 * 注销请求体（WBS-3.3.2 hifi §1.1 W3）：二次确认 API 强表达（沿 confirmDissolve 先例）——
 * 缺省/null/false 一律 400 通用参数码（应用服务判定）。
 */
public record CancellationRequest(Boolean confirmCancellation) {
}
