package com.ctds.std.did;

/**
 * 互认验证结论（业务口径三值；取值与 DID 服务 ADR-017 §2.9 验证结论口径一致——本枚举为<b>结论</b>词汇，
 * 非 DID 状态枚举）：PASS = 通过；FAIL = 不通过；UNAVAILABLE = 互认通道不可用（系统态，不冒充"不通过"）。
 */
public enum InteropResult {
    PASS,
    FAIL,
    UNAVAILABLE
}
