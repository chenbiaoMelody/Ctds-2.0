package com.ctds.common.crypto;

/**
 * 版本化密钥供给（WBS-2.6.3 密钥轮换载体，hifi §2；契约留痕 ADR-006 变更注记/ADR-015）。
 * KMS 托管密钥实现本接口：加密写当前版本号进 v2 信封，解密按信封内版本取历史密钥——轮换后旧密文不失效。
 * 本地文件实现不实现本接口（无版本概念，保持 v1 信封语义）。
 */
public interface VersionedKeyProvider extends KeyProvider {

    /**
     * 按密钥编号+版本取 SM4 密钥材料（16 字节，新副本）。
     *
     * @param keyRef  密钥编号（业务别名，非密钥材料，可入日志）
     * @param version 密钥版本号（随密文信封携带）
     * @return 密钥字节（新副本，调用方修改不影响供给源）
     * @throws com.ctds.common.errorcode.BizException 编号或版本查无 / 密钥服务不可用 → CRYPTO_KEY_UNAVAILABLE
     */
    byte[] sm4Key(String keyRef, int version);

    /**
     * 该密钥编号的当前版本号（新加密一律使用此版本）。
     *
     * @throws com.ctds.common.errorcode.BizException 编号查无 / 密钥服务不可用 → CRYPTO_KEY_UNAVAILABLE
     */
    int currentVersion(String keyRef);
}
