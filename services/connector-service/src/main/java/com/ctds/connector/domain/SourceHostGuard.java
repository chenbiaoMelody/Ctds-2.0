package com.ctds.connector.domain;

import com.ctds.connector.sdk.spi.SourceType;

/**
 * 调用边界主机防护端口（WBS-3.6.6，DB-91 闭环；hifi §4 / §7.1 / §7.2）。
 *
 * <p>在自检 / 读取<b>调用适配器前</b>解析目标主机（含对象存储凭据中的预签名 URL 与 JDBC host），
 * 命中回环 / 内网 / 链路本地 / 不可解析即拒绝，保证拒绝时<b>适配器零触达</b>（无 SSRF、无 DNS 漂移孤儿请求）。
 * 受理期不做解析（避免校验期联网与 TOCTOU）。</p>
 */
public interface SourceHostGuard {

    /**
     * 校验源目标主机是否允许访问；不允许即抛 {@code 1010C0010} 业务异常。
     *
     * @param type            源类型（决定从 endpoint / 凭据中提取哪些主机）
     * @param endpoint        库中源地址（已按受理规则复核）
     * @param credentialPlain 解密后的凭据明文（仅本次调用使用；对象存储 = 预签名 URL）
     */
    void assertAllowed(SourceType type, String endpoint, String credentialPlain);
}
