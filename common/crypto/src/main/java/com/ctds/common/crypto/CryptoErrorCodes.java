package com.ctds.common.crypto;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 国密加密封装组件码表（模块位 1001，契约见 ADR-006 与 docs/designs/WBS-2.4.6-hifi.md）。
 * 对外文案为服务端常量，禁止拼接用户输入（防响应回显，红线：不暴露内部实现）。
 */
public final class CryptoErrorCodes {

    /** 输入不合法：null/空/超长入参、非 CTDS 信封、信封版本不认识、hex/Base64 格式坏。→ 400 */
    public static final ErrorCode CRYPTO_INPUT_INVALID = ErrorCode.of("1001C0001");
    /** 数据校验未通过：密文被篡改/截断/错密钥解密失败（完整性保护生效）。→ 400 */
    public static final ErrorCode CRYPTO_DATA_REJECTED = ErrorCode.of("1001C0002");
    /** 密钥服务暂不可用：keyRef 在密钥源查无（系统/配置类）。→ 500 */
    public static final ErrorCode CRYPTO_KEY_UNAVAILABLE = ErrorCode.of("1001S0001");
    /** 加解密操作失败：底层算法库未预期异常（系统类，不暴露内部消息）。→ 500 */
    public static final ErrorCode CRYPTO_OPERATION_FAILED = ErrorCode.of("1001S0002");

    private CryptoErrorCodes() {
    }
}
