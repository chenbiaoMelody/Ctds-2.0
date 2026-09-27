package com.ctds.space.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 空间服务码表（模块位 06，1006 段定稿，WBS-3.2.3 hifi §2；1000 平台/1001 国密/1002 幂等锁+KMS/1003
 * std-adapter/1004 主体/1005 DID 已占用，占用留痕 ADR-016，沿 ADR-015 §3 惯例）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3）；精确 HTTP 映射见 interfaces.SpaceExceptionHandler。
 */
public final class SpaceErrorCodes {

    /** 主体未入驻（统一文案，不区分"不存在/未入驻"——防枚举，规格行为 1 规则 1）。→ 403 */
    public static final ErrorCode ADMISSION_REQUIRED = ErrorCode.of("1006C0001");
    /** 统一资格文案（创建与启用共用；不暴露"主体是否存在"的差异——验收标准防枚举口径）。 */
    public static final String ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法执行该操作";
    /** 空间状态不允许该动作（含终态再动作、未启用不可变更）。→ 409 */
    public static final ErrorCode SPACE_STATUS_GATE = ErrorCode.of("1006C0002");
    public static final String SPACE_STATUS_GATE_MESSAGE = "空间当前状态不允许该操作";
    /** 同一所有者已有同名空间（归一化后判定）。→ 409 */
    public static final ErrorCode SPACE_NAME_TAKEN = ErrorCode.of("1006C0003");
    public static final String SPACE_NAME_TAKEN_MESSAGE = "同一所有者已存在同名空间";
    /** 名称已被历史空间锁定（解散后全平台不可复用，行为 2 规则 4）。→ 409，同码不同文案 */
    public static final String SPACE_NAME_LOCKED_MESSAGE = "空间名称已被锁定，不可复用";
    /** 空间不存在。→ 404 */
    public static final ErrorCode SPACE_NOT_FOUND = ErrorCode.of("1006C0004");
    public static final String SPACE_NOT_FOUND_MESSAGE = "空间不存在";
    /** 创建要素缺失/超长（逐字段提示）。→ 400 */
    public static final ErrorCode SPACE_ELEMENT_MISSING = ErrorCode.of("1006C0005");
    /** 解散二次确认缺失（confirmDissolve ≠ true，缺省/null/false 一律拒绝）。→ 400 */
    public static final ErrorCode DISSOLVE_CONFIRM_REQUIRED = ErrorCode.of("1006C0006");
    public static final String DISSOLVE_CONFIRM_REQUIRED_MESSAGE = "缺少解散二次确认（confirmDissolve 必须为 true）";
    /** 非授权主体执行该动作（逐动作判定失败，含 member 越权；DENIED 留痕联动）。→ 403 */
    public static final ErrorCode SPACE_ACCESS_DENIED = ErrorCode.of("1006C0007");
    public static final String SPACE_ACCESS_DENIED_MESSAGE = "无权限执行该空间操作";
    /** 拒绝留痕理由（space_action_log.reason，服务端常量）。 */
    public static final String ACCESS_DENIED_LOG_REASON = "操作者不具备该动作所需权限";
    /** 主体服务不可达/失败（UNAVAILABLE 统一文案，不冒充 1006C0001——WBS-3.2.3 hifi §4）。→ 503 */
    public static final ErrorCode SUBJECT_SERVICE_UNAVAILABLE = ErrorCode.of("1006S0001");
    public static final String SUBJECT_SERVICE_UNAVAILABLE_MESSAGE = "主体服务暂不可用，请稍后重试";

    private SpaceErrorCodes() {
    }
}
