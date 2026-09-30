package com.ctds.catalog.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 目录与资源服务码表（模块位 07，1007 段定稿，WBS-3.3.2 hifi §2；1000 平台/1001 国密/1002 幂等锁+KMS/
 * 1003 std-adapter/1004 主体/1005 DID/1006 空间已占用，占用留痕 ADR-016，沿 ADR-015 §3 惯例）。
 * 对外文案为服务端常量，禁止拼接用户输入（章程 4.3）；精确 HTTP 映射见 interfaces.CatalogExceptionHandler。
 */
public final class CatalogErrorCodes {

    /** 同空间归一化后重名（含注销名锁定命中，行为 1 规则 5 / 行为 2 规则 3）。→ 409 */
    public static final ErrorCode DATASET_NAME_DUPLICATED = ErrorCode.of("1007C0001");
    public static final String DATASET_NAME_DUPLICATED_MESSAGE = "同一空间已存在同名资源";
    /** 注销名锁定命中文案（同码不同文案，沿 space 先例）。 */
    public static final String DATASET_NAME_LOCKED_MESSAGE = "资源名称已被本空间历史资源锁定，不可复用";
    /** 空间状态不可登记（未启用/冻结/解散一律拒绝，行为 1 规则 2——冻结拒新增 = 空间规格行为 2 规则 3 兑现）。→ 409 */
    public static final ErrorCode DATASET_SPACE_STATE_FORBIDDEN = ErrorCode.of("1007C0002");
    public static final String DATASET_SPACE_STATE_FORBIDDEN_MESSAGE = "空间当前状态不允许登记资源";
    /** 申报为重要数据，拒收登记（分级规范 §4.5-3 硬约束，代码强制，行为 1 规则 4）。→ 409 */
    public static final ErrorCode DATASET_IMPORTANT_REJECTED = ErrorCode.of("1007C0003");
    public static final String DATASET_IMPORTANT_REJECTED_MESSAGE = "申报为重要数据的资源暂不受理登记";
    /** 分类级别变更只能收紧就高（行为 2 规则 1——下调/放宽方向一律拒绝）。→ 409 */
    public static final ErrorCode DATASET_LEVEL_TIGHTEN_ONLY = ErrorCode.of("1007C0004");
    public static final String DATASET_LEVEL_TIGHTEN_ONLY_MESSAGE = "分类级别变更只能就高收紧，不可放宽";
    /** 资源不存在或无权访问（读面防枚举同形，行为 7 规则 2——不区分"不存在"与"非本人"）。→ 404 */
    public static final ErrorCode DATASET_NOT_FOUND_OR_NO_ACCESS = ErrorCode.of("1007C0005");
    public static final String DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE = "资源不存在或无权访问";
    /** 非登记主体无权操作（写面越权拒绝，行为 2 规则 5/行为 7 规则 1——含空间管理员，DENIED 留痕联动）。→ 403 */
    public static final ErrorCode DATASET_FORBIDDEN = ErrorCode.of("1007C0006");
    public static final String DATASET_FORBIDDEN_MESSAGE = "仅登记主体本人可执行该资源操作";
    /** 主体未入驻统一文案（行为 1 规则 1 防枚举：不区分"主体不存在"与"主体未入驻"；
     * 码值复用 1007C0006——hifi §2 码表未设独立资格码，hifi §1.1 W1 主要错误码列亦未单列，
     * 403 无权限语义下以本常量承载统一出站文案，实现口径已在交付说明登记）。 */
    public static final String ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法登记资源";
    /** 资源已注销，终态不可再操作（行为 2 规则 4——终态自环显式门槛）。→ 409 */
    public static final ErrorCode DATASET_ALREADY_DELETED = ErrorCode.of("1007C0007");
    public static final String DATASET_ALREADY_DELETED_MESSAGE = "资源已注销，不可再变更或注销";
    /** 名称为空/超长/含控制字符（行为 1 规则 5——归一化后校验）。→ 400 */
    public static final ErrorCode DATASET_NAME_INVALID = ErrorCode.of("1007C0008");
    public static final String DATASET_NAME_INVALID_MESSAGE = "资源名称不能为空或超出长度限制";
    /** 主体服务不可达/失败（UNAVAILABLE 统一文案，不冒充资格拒绝——hifi §5）。→ 503 */
    public static final ErrorCode SUBJECT_SERVICE_UNAVAILABLE = ErrorCode.of("1007S0001");
    public static final String SUBJECT_SERVICE_UNAVAILABLE_MESSAGE = "主体服务暂不可用，请稍后重试";
    /** 空间服务不可达/失败（UNAVAILABLE 统一文案，不冒充"非成员/空间不存在"——hifi §5）。→ 503 */
    public static final ErrorCode SPACE_SERVICE_UNAVAILABLE = ErrorCode.of("1007S0002");
    public static final String SPACE_SERVICE_UNAVAILABLE_MESSAGE = "空间服务暂不可用，请稍后重试";
    /**
     * 错误码尾号（dataset_action_log.reason_code 口径，hifi §3.3：DENIED 时落错误码尾号，
     * 如 1007C0003 → C0003；服务端常量，不含用户输入）。
     */
    public static String tailOf(final ErrorCode errorCode) {
        final String value = errorCode.value();
        return value.length() <= 5 ? value : value.substring(value.length() - 5);
    }

    private CatalogErrorCodes() {
    }
}
