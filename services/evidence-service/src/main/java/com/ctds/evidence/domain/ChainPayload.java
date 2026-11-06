package com.ctds.evidence.domain;

/**
 * 上链载荷（ADR-004 §3-3 白名单）：payloadJson 仅五项、固定键序、无空白；
 * payloadDigest = 内容摘要原值（用于账本幂等 uk_chain_payload）。
 */
public record ChainPayload(String payloadJson, String payloadDigest) {
}
