package com.ctds.std.did;

/**
 * 互认验证失败/不可用原因（业务口径五值，取值与 3.1.9 验证原因口径<b>逐字一致、不新增取值</b>；PASS 时为 null）：
 * 签名核验失败 / 状态核验失败 / 对端绑定核验失败 / 对端未登记 / 互认通道不可用。
 */
public enum InteropReason {
    SIGNATURE_INVALID,
    REVOKED,
    SUBJECT_BINDING_FAILED,
    NOT_REGISTERED,
    BINDING_UNAVAILABLE
}
