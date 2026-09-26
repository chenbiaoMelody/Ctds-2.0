package com.ctds.space.interfaces.dto;

/**
 * 解散请求（WBS-3.2.3 hifi §1 端点 5，Q3-A）：confirmDissolve 语义 = 请求体显式 true
 * （缺省/null/false 一律 1006C0006，hifi §8）；理由可选（≤256，留痕 reason）。
 */
public record DissolveRequest(Boolean confirmDissolve, String reason) {
}
