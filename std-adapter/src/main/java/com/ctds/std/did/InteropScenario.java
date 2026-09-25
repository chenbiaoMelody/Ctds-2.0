package com.ctds.std.did;

/** 预置样例场景（演示期模拟对端三态）：有效 / 对端已吊销 / 签名被篡改。 */
public enum InteropScenario {
    VALID,
    PEER_REVOKED,
    TAMPERED
}
