package com.ctds.connector.sdk.adapter.gov;

/**
 * 凭据解析面（ADR-026 补记）：平台侧注入"凭据引用 → 明文"的解密能力。
 *
 * <p>SDK 不感知密钥与存储：明文只在适配器调用期间于内存流转，<b>不落日志、不入登记模型、
 * 不回显响应</b>（凭据引用制，规格行为 12 规则 2）。密钥不可用时实现方应直接抛出（平台侧
 * 映射为 503 fail-closed），不得降级为明文。</p>
 */
@FunctionalInterface
public interface CredentialResolver {

    /**
     * 解析凭据引用。
     *
     * @param credentialRef 平台侧签发的凭据不透明引用
     * @return 凭据明文（仅本次调用使用）
     */
    String resolve(String credentialRef);
}
