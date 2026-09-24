package com.ctds.std.did;

/**
 * 预置样例（业务语言）：样例标识 / 对端 DID / 场景 / 原文与签名（Base64）/ 样例公钥（130 hex 非压缩点）
 * / 对端状态断言（已吊销、绑定失效）/ 预期结论与原因（供演示走查与测试比对）。
 */
public record InteropSample(
        String sampleId,
        String did,
        InteropScenario scenario,
        String data,
        String signature,
        String publicKeyHex,
        boolean peerRevoked,
        boolean peerBindingLost,
        InteropResult expectedResult,
        InteropReason expectedReason) {
}
