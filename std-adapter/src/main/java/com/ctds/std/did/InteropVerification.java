package com.ctds.std.did;

/**
 * 互认验证结论（业务语言）：对端空间标识 / 被验证的 DID / 结论 / 原因（PASS 时为 null）。
 * <p>本域不做时间决策：留痕与响应时间戳由调用方（DID 服务）以<b>应用时钟</b>写入（ADR-017 §2.2）。</p>
 */
public record InteropVerification(String peerSpace, String did, InteropResult result, InteropReason reason) {
}
