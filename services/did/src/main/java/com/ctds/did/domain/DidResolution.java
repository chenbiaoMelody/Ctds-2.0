package com.ctds.did.domain;

/**
 * 解析结果（WBS-3.1.9 行为 2）：DID 文档公开要素（原样 JSON 串）+ 当前状态（仅 ACTIVE/REVOKED 两值）。
 */
public record DidResolution(String did, DidStatus status, String documentJson) {
}