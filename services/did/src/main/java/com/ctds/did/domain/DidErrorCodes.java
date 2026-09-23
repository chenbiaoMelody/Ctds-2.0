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
    /** 解析目标未登记（明确业务答复，不伪装系统异常；WBS-3.1.9 行为 2 规则 3）。→ 400 */
    public static final ErrorCode DID_NOT_REGISTERED = ErrorCode.of("1005B0003");
    /** 验证参数不合法：data/signature 缺失、非法 Base64、空内容或超上限（WBS-3.1.9 行为 3）。→ 400 */
    public static final ErrorCode DID_VERIFICATION_INPUT_INVALID = ErrorCode.of("1005C0004");
    /** 验证内部错误：注册表读取/文档公钥解析等非输入类故障（WBS-3.1.9 hifi §6）。→ 500 */
    public static final ErrorCode DID_VERIFICATION_INTERNAL_ERROR = ErrorCode.of("1005S0002");

    private DidErrorCodes() {
    }
}
