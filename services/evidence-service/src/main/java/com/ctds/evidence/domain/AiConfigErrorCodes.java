package com.ctds.evidence.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * WBS-3.10.4 Q6-A：1014 段启用（3.10/AI 域；沿既有 1011、1012 与 3.9 系统管理域分域口径，
 * C-10.1-10.2 规格 :236）。最小码值集：校验拒绝一码 + 消息常量细分（拒绝原因文案可区分，
 * 规格 B2-2）。401 / 403 仍走 common-auth 公共码，由统一异常出口映射；
 * ErrorType.C 默认 400 + 原文案透传（EvidenceExceptionHandler default 分支），无需新增映射。
 */
public final class AiConfigErrorCodes {

    /** 保存载荷参数校验拒绝（细分原因见 MSG_* 常量，同码不同文）。 */
    public static final ErrorCode PARAM_INVALID = ErrorCode.of("1014C0001");

    /** 供应商编号不在封闭清单内（清单外 / 缺行 / 多行）。 */
    public static final String MSG_PROVIDER_NOT_IN_LIST = "供应商编号不在封闭清单内（glm/deepseek/qwen/kimi）";

    /** 默认供应商不在封闭清单内。 */
    public static final String MSG_DEFAULT_PROVIDER_NOT_IN_LIST = "默认供应商不在封闭清单内（glm/deepseek/qwen/kimi）";

    /** token 预算须为 ≥0 整数（0=未设限）。 */
    public static final String MSG_BUDGET_INVALID = "token 预算须为 ≥0 的整数";

    /** 服务地址须为 http(s) URL 或留空（空=未配置）。 */
    public static final String MSG_BASE_URL_INVALID = "服务地址须为 http(s) URL，或留空表示未配置";

    /** 默认模型名不得超过 128 字符。 */
    public static final String MSG_MODEL_INVALID = "默认模型名不得超过 128 字符";

    /** 网关开关缺失或不合法（载荷必须显式携带布尔值）。 */
    public static final String MSG_ENABLED_INVALID = "网关开关须为布尔值";

    private AiConfigErrorCodes() {
    }
}
