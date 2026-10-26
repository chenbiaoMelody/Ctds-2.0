package com.ctds.connector.sdk;

/**
 * 平台访问令牌来源（lofi §1-3）。
 *
 * <p>SDK 经平台网关调用统一携带 {@code Authorization: Bearer &lt;token&gt;}；
 * 网关剥离入站伪造身份头、按令牌认定权威身份（ADR-005 §3-7）——SDK 不自行注入身份头。
 * 演示期为静态令牌；刷新型令牌体系归 3.9.1 统一演进。</p>
 */
public interface TokenProvider {

    /** 返回当前有效令牌值（不含 "Bearer " 前缀）。 */
    String token();
}
