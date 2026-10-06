package com.ctds.contract.domain;

/**
 * DID 能力端口（WBS-3.4.3 hifi §1/§6.4；Q5-A 复用 did 既有三能力）：解析（归属与状态校验）
 * / 代签（演示签名入口，签署内容 = 条款快照 SM3 哈希）/ 验签（三查：签名/状态/绑定）。
 * fail-closed 口径：任何不可达/关闭/解析失败一律 UNAVAILABLE，不冒充签署结论（hifi §3
 * 1008S0002/1008C0018 分工：身份不成立 → C0018；服务不可用 → S0002）。
 */
public interface DidPort {

    /**
     * 解析（签署前校验：未登记 → NOT_REGISTERED；document.controller 供归属断言；
     * status = ACTIVE/REVOKED 原值）。
     */
    DidBinding resolve(String did);

    /** 代签（演示签名入口；成功返回 SM2 DER 签名 Base64；入口关闭/不可达 → UNAVAILABLE）。 */
    DidSignResult sign(String did, String contentHash);

    /** 验签（三查结论；did 服务侧明确答复 UNAVAILABLE 与传输失败分列——R9 不冒充结论口径）。 */
    DidVerifyResult verify(String did, String dataBase64, String signatureBase64);

    /** 解析三态（FOUND 时 controller/status 有值）。 */
    record DidBinding(State state, String controller, String status) {

        public enum State { FOUND, NOT_REGISTERED, UNAVAILABLE }

        public static DidBinding found(final String controller, final String status) {
            return new DidBinding(State.FOUND, controller, status);
        }

        public static DidBinding notRegistered() {
            return new DidBinding(State.NOT_REGISTERED, null, null);
        }

        public static DidBinding unavailable() {
            return new DidBinding(State.UNAVAILABLE, null, null);
        }
    }

    /** 代签两态（SIGNED 时 signatureBase64 = SM2 DER 签名 Base64）。 */
    record DidSignResult(State state, String signatureBase64) {

        public enum State { SIGNED, UNAVAILABLE }

        public static DidSignResult signed(final String signatureBase64) {
            return new DidSignResult(State.SIGNED, signatureBase64);
        }

        public static DidSignResult unavailable() {
            return new DidSignResult(State.UNAVAILABLE, null);
        }
    }

    /** 验签结果（did 服务侧结论原样承载：PASS/FAIL/UNAVAILABLE + 原因码；transportFailure = 传输层失败）。 */
    record DidVerifyResult(Outcome outcome, String reason, boolean transportFailure) {

        public enum Outcome { PASS, FAIL, UNAVAILABLE }

        public static DidVerifyResult of(final Outcome outcome, final String reason) {
            return new DidVerifyResult(outcome, reason, false);
        }

        public static DidVerifyResult transportUnreachable() {
            return new DidVerifyResult(Outcome.UNAVAILABLE, null, true);
        }
    }
}
