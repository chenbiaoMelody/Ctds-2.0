package com.ctds.common.crypto;

/**
 * SM4 密钥供给接口（Q3 模式 A 落地件，hifi"KeyProvider"节）。
 * V1.0 默认本地文件实现；真实 KMS（2.6.3）定义本接口 Bean 即整体替换（业务代码零改动）。
 */
public interface KeyProvider {

    /**
     * 按密钥编号取 SM4 密钥材料（16 字节）。
     *
     * @param keyRef 密钥编号（业务别名，非密钥材料，可入日志）
     * @return 密钥字节（新副本，调用方修改不影响供给源）
     * @throws com.ctds.common.errorcode.BizException 编号查无 → CRYPTO_KEY_UNAVAILABLE
     */
    byte[] sm4Key(String keyRef);
}
