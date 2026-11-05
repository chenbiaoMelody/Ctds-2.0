package com.ctds.evidence.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 存证域错误码表（1012 段，hifi §5 定稿；前置检查实测 Java 零占用、docs 仅本卡设计文件）。
 *
 * <p>段位占用：C0001 记录不存在 / C0002 参数不合法 / C0003 状态冲突 /
 * S0001 存证链不可达 / S0002 取号或落库失败。空位：C0004~C0009、S0003~S0009 留白，
 * 后续同域实施包按序占用，不新增 1013 段（ADR-005 一域一段惯例）。</p>
 */
public final class EvidenceErrorCodes {

    /** 存证记录不存在（404；详情 / 核验 / 重试同码同文防枚举）。 */
    public static final ErrorCode RECORD_NOT_FOUND = ErrorCode.of("1012C0001");
    public static final String RECORD_NOT_FOUND_MESSAGE = "存证记录不存在";

    /** 存证参数不合法（400：环节 / 操作类型 / 单据号 / 时间 / 主体 / 分页 / 时间窗）。 */
    public static final ErrorCode PARAM_INVALID = ErrorCode.of("1012C0002");
    public static final String PARAM_INVALID_MESSAGE = "请求参数不合法，请检查后重试";

    /** 当前存证状态不允许该操作（409：已上链再重试）。 */
    public static final ErrorCode STATE_CONFLICT = ErrorCode.of("1012C0003");
    public static final String STATE_CONFLICT_MESSAGE = "当前存证状态不支持该操作";

    /** 存证链不可达（503：上链 / 重试 / 取链上记录失败）。 */
    public static final ErrorCode CHAIN_UNAVAILABLE = ErrorCode.of("1012S0001");
    public static final String CHAIN_UNAVAILABLE_MESSAGE = "存证链暂不可用，请稍后重试";

    /** 存证取号或落库失败（500，非业务可修复）。 */
    public static final ErrorCode STORAGE_FAILED = ErrorCode.of("1012S0002");
    public static final String STORAGE_FAILED_MESSAGE = "存证服务暂不可用，请稍后重试";

    private EvidenceErrorCodes() {
    }
}
