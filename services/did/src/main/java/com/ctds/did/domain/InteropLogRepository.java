package com.ctds.did.domain;

/**
 * 互认留痕仓储端口（实现 = 基础设施层 JDBC 仓储，沿 3.1.9 `DidRepository`/`DidJdbcRepository` 分层先例）。
 * <p>只写不留原文：入参仅含四要素与失败原因。</p>
 */
public interface InteropLogRepository {

    void insert(InteropLog log);
}
