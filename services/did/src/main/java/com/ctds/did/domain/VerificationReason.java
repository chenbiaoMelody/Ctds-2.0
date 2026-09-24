package com.ctds.did.domain;

/**
 * 验证失败/不可用原因（WBS-3.1.9 hifi §1 B6~B9/B12）：四类失败 + 不可用类。
 * 对外文案为服务端常量，禁止拼接用户输入。
 */
public enum VerificationReason {
    /** 签名核验失败：数据被篡改 / 签名伪造 / 签名结构非法（收敛同一类）。 */
    SIGNATURE_INVALID,
    /** 状态核验失败：DID 已吊销。 */
    REVOKED,
    /** 主体绑定核验失败：关联主体当前非"已入驻"。 */
    SUBJECT_BINDING_FAILED,
    /** 目标未登记（身份主张不成立；与解析侧 1005B0003 表征差异见 hifi §6）。 */
    NOT_REGISTERED,
    /** 绑定核验服务不可用（系统态，不冒充"验证不通过"）。 */
    BINDING_UNAVAILABLE
}