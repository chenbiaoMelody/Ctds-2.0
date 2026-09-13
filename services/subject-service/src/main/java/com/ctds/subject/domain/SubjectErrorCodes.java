package com.ctds.subject.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 主体服务码表（模块位 04，1004 段；1000 平台/1001 国密/1002 幂等锁+KMS/1003 std-adapter 已占用，
 * 占用留痕 ADR-016，沿 ADR-015 §3 惯例）。对外文案为服务端常量，禁止拼接用户输入（章程 4.3）。
 */
public final class SubjectErrorCodes {

    /** 统一社会信用代码已存在且当前状态不可重报。→ 400 */
    public static final ErrorCode SUBJECT_ALREADY_REGISTERED = ErrorCode.of("1004B0001");
    /** 当前状态不可撤销（非待认证或已撤销过）。→ 400 */
    public static final ErrorCode SUBJECT_CANCEL_NOT_ALLOWED = ErrorCode.of("1004C0001");

    // ==== 认证类错误码（WBS-3.1.3，占用留痕 ADR-016 §2.1 补记；文案见 hifi 错误码表） ====
    /** 证照影像无法识别，请重传。→ 400 */
    public static final ErrorCode CERT_LICENSE_UNRECOGNIZABLE = ErrorCode.of("1004B0002");
    /** 确认的统一社会信用代码与证照识别结果不一致。→ 400 */
    public static final ErrorCode CERT_USCC_MISMATCH = ErrorCode.of("1004B0003");
    /** 法人信息与证照识别结果不一致。→ 400 */
    public static final ErrorCode CERT_LEGAL_PERSON_MISMATCH = ErrorCode.of("1004B0004");
    /** 当日核验失败次数已达上限。→ 400 */
    public static final ErrorCode CERT_VERIFY_LIMIT_REACHED = ErrorCode.of("1004B0005");
    /** 证照尚未上传/核对确认。→ 400 */
    public static final ErrorCode CERT_LICENSE_NOT_CONFIRMED = ErrorCode.of("1004B0006");
    /** 当前状态不允许执行认证操作（状态门槛）。→ 400 */
    public static final ErrorCode CERT_STATE_NOT_ALLOWED = ErrorCode.of("1004C0002");
    /** 认证渠道不可用（fail-fast，可重试，不计核验失败次数）。→ 503（精确映射见 CertificationExceptionHandler） */
    public static final ErrorCode CERT_CHANNEL_UNAVAILABLE = ErrorCode.of("1004S0001");
    /** 渠道不可用出站文案（S 型码经本地处理器精确映射 503 并保留本文案，不走全局脱敏）。 */
    public static final String CERT_CHANNEL_UNAVAILABLE_MESSAGE = "认证服务暂不可用，请稍后重试";

    private SubjectErrorCodes() {
    }
}
