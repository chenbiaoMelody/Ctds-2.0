package com.ctds.did.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * DID 服务码表（模块位 05，1005 段；沿 ADR-005 §3.5 顺延制度，占用留痕 ADR-017）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3：不暴露内部实现）。
 */
public final class DidErrorCodes {

    /** 参数不合法：subjectNo 缺失/非法、reason 超长。→ 400 */
    public static final ErrorCode DID_PARAM_INVALID = ErrorCode.of("1005C0001");
    /** 吊销理由必填（非空白）。→ 400 */
    public static final ErrorCode DID_REVOKE_REASON_REQUIRED = ErrorCode.of("1005C0002");
    /** 状态门槛：非有效 DID 不可吊销。→ 400 */
    public static final ErrorCode DID_REVOKE_NOT_ACTIVE = ErrorCode.of("1005C0003");
    /** 未找到待签发记录（重试无对象）。→ 400 */
    public static final ErrorCode DID_NO_PENDING_ISSUANCE = ErrorCode.of("1005B0001");
    /** 无已吊销记录可重签。→ 400 */
    public static final ErrorCode DID_NO_REVOKED_TO_REISSUE = ErrorCode.of("1005B0002");
    /** 签发内部失败：KMS 不可达经触发接口收敛为 PENDING_ISSUE（不直出）；文档组装/激活等内部异常按本码直出 500。→ 500 */
    public static final ErrorCode DID_ISSUANCE_INTERNAL_ERROR = ErrorCode.of("1005S0001");

    private DidErrorCodes() {
    }
}
