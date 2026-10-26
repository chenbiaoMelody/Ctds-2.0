package com.ctds.connector.domain;

/**
 * 主体当前 DID 状态只读端口（单一事实源在 DID 域，ADR-017 内部面）。
 * 任何不可判定情形一律返回 {@link CurrentDid#unavailable()}，由调用方 fail-closed 出 503。
 */
public interface DidStatusPort {

    CurrentDid current(String subjectNo);
}
