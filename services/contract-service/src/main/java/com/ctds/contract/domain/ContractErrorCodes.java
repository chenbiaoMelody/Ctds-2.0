package com.ctds.contract.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 合约服务码表（模块位 08，1008 段定稿，WBS-3.4.2 hifi §3；1000 平台/1001 国密/1002 幂等锁+KMS/
 * 1003 std-adapter/1004 主体/1005 DID/1006 空间/1007 目录已占用，1008 预留于规格 C-4.1~4.3 §7 Q8
 * 裁决〔已裁决采 A〕，沿 ADR-015 §3 惯例）。对外文案为服务端常量，禁止拼接用户输入（章程 4.3）；
 * 精确 HTTP 映射见 interfaces.ContractExceptionHandler。
 */
public final class ContractErrorCodes {

    /** 模板不存在或不可用（浏览面读防枚举同形：不存在 / 已停用同码同文案——行为 1 规则 4/5，
     * Q6-A 最严口径"停用即不可见"；hifi V1.1 §3）。→ 404 */
    public static final ErrorCode TEMPLATE_NOT_FOUND_OR_UNAVAILABLE = ErrorCode.of("1008C0001");
    public static final String TEMPLATE_NOT_FOUND_OR_UNAVAILABLE_MESSAGE = "模板不存在或不可用";
    /** 非平台运营方无权维护模板（写面越权拒绝，行为 1 规则 1——DENIED_MANAGE 留痕联动；
     * 维护权判定在应用服务单点承载，hifi V1.1 §2.1）。→ 403 */
    public static final ErrorCode TEMPLATE_FORBIDDEN = ErrorCode.of("1008C0002");
    public static final String TEMPLATE_FORBIDDEN_MESSAGE = "无权进行模板维护操作";
    /** 主体未入驻统一码（行为 1 规则 5 防枚举：不区分"主体不存在"与"主体未入驻"；
     * 同码双文案沿 catalog ADMISSION_REQUIRED_MESSAGE 先例——浏览语境与维护语境分表意）。→ 404 */
    public static final ErrorCode ADMISSION_REQUIRED = ErrorCode.of("1008C0003");
    /** 主体未入驻统一文案·浏览场景（hifi §3；对齐 C-1.1 出站口径）。 */
    public static final String BROWSE_ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法浏览模板";
    /** 主体未入驻统一文案·维护场景（剧本 C-4.1 S3-2 判定面）。 */
    public static final String MAINTAIN_ADMISSION_REQUIRED_MESSAGE = "主体未入驻或不存在，无法维护模板";
    /** 条款框架不符合模板规范（缺必填槽位/未知槽位键/结构非法/重复槽位，行为 1 规则 6 前置——
     * 条款一致性由模板框架保证；逐槽位明细入服务端日志、响应仅本常量文案（hifi V1.2 §3 补正⑥，
     * 对外不回显用户输入——章程 4.3）。→ 400 */
    public static final ErrorCode CLAUSE_FRAMEWORK_INVALID = ErrorCode.of("1008C0004");
    public static final String CLAUSE_FRAMEWORK_INVALID_MESSAGE = "条款框架不符合模板规范";
    /** 同类型下已存在同名模板（归一化判定，uk_type_norm_name 兜底；并发命中转译本码）。→ 409 */
    public static final ErrorCode TEMPLATE_NAME_DUPLICATED = ErrorCode.of("1008C0005");
    public static final String TEMPLATE_NAME_DUPLICATED_MESSAGE = "同类型下模板名称已存在";
    /** 模板不存在（运营面视角——维护与运营读面直述，不防枚举，hifi V1.1 §3 1008C0006）。→ 404 */
    public static final ErrorCode TEMPLATE_NOT_FOUND = ErrorCode.of("1008C0006");
    public static final String TEMPLATE_NOT_FOUND_MESSAGE = "模板不存在";
    /** 模板已处于目标状态（同态重复启停，状态机门槛——hifi §6；DENIED_MANAGE 留痕联动）。→ 409 */
    public static final ErrorCode TEMPLATE_STATE_FORBIDDEN = ErrorCode.of("1008C0007");
    public static final String TEMPLATE_STATE_FORBIDDEN_MESSAGE = "模板已处于目标状态";
    /** 请求参数不合法（通用逐字段：名称/类型缺失、非法 type 绑定、超长等，hifi §3 1008C0008）。→ 400 */
    public static final ErrorCode TEMPLATE_PARAM_INVALID = ErrorCode.of("1008C0008");
    public static final String TEMPLATE_PARAM_INVALID_MESSAGE = "请求参数不合法";
    /** 模板正在被其他操作修改（并发修订撞 uk_template_version 唯一索引兜底转译，沿 catalog
     * T15 并发兜底先例；新增并发撞 uk_template_no 编号冲突同码兜底——V1.2 补正⑨）。→ 409 */
    public static final ErrorCode TEMPLATE_CONCURRENT_MODIFICATION = ErrorCode.of("1008C0009");
    public static final String TEMPLATE_CONCURRENT_MODIFICATION_MESSAGE = "模板正在被其他操作修改，请重试";
    /** 主体资格服务不可达/失败（UNAVAILABLE 统一文案，不冒充资格拒绝——沿 catalog 1007S0001 先例）。→ 503 */
    public static final ErrorCode SUBJECT_SERVICE_UNAVAILABLE = ErrorCode.of("1008S0001");
    public static final String SUBJECT_SERVICE_UNAVAILABLE_MESSAGE = "主体资格服务暂不可用，请稍后重试";
    /**
     * 错误码尾号（contract_template_action_log.reason_code 口径，hifi §4：DENIED 时落错误码尾号，
     * 如 1008C0002 → C0002；服务端常量，不含用户输入）。
     */
    public static String tailOf(final ErrorCode errorCode) {
        final String value = errorCode.value();
        return value.length() <= 5 ? value : value.substring(value.length() - 5);
    }

    private ContractErrorCodes() {
    }
}
