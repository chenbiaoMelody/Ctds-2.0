package com.ctds.delivery.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * 沙箱错误码（1009 段顺延；0001~0004 / S0001 网关，0005~0009 / S0002~S0003 交付，hifi §7.2）。
 * 文案均为服务端常量，禁止拼接用户输入；不含镜像 / 路径 / 容器 / 命令。
 */
public final class SandboxErrorCodes {

    /** 沙箱任务不存在或无权查看（同码同文防枚举）。→ 404 */
    public static final ErrorCode SANDBOX_NOT_VISIBLE = ErrorCode.of("1009C0010");
    public static final String SANDBOX_NOT_VISIBLE_MESSAGE = "沙箱任务不存在或无权查看";

    /** 沙箱任务当前状态不允许该操作（已到期 / 已关闭）。→ 409 */
    public static final ErrorCode SANDBOX_STATE_CONFLICT = ErrorCode.of("1009C0011");
    public static final String SANDBOX_STATE_CONFLICT_MESSAGE = "沙箱任务当前状态不允许该操作";

    /** 原始数据不可直接导出。→ 403 */
    public static final ErrorCode SANDBOX_DATA_DENIED = ErrorCode.of("1009C0012");
    public static final String SANDBOX_DATA_DENIED_MESSAGE = "原始数据不可直接导出";

    /** 沙箱任务数量已达上限。→ 429 */
    public static final ErrorCode SANDBOX_QUOTA_EXCEEDED = ErrorCode.of("1009C0013");
    public static final String SANDBOX_QUOTA_EXCEEDED_MESSAGE = "沙箱任务数量已达上限";

    /** 作业不在允许清单内。→ 400 */
    public static final ErrorCode SANDBOX_JOB_NOT_ALLOWED = ErrorCode.of("1009C0014");
    public static final String SANDBOX_JOB_NOT_ALLOWED_MESSAGE = "作业不在允许清单内";

    /** 结果物不存在或无权查看（同码同文防枚举，结果物级；WBS-3.5.5）。→ 404 */
    public static final ErrorCode SANDBOX_RESULT_NOT_VISIBLE = ErrorCode.of("1009C0015");
    public static final String SANDBOX_RESULT_NOT_VISIBLE_MESSAGE = "结果物不存在或无权查看";

    /** 结果物当前状态不允许该操作（未审核 / 审核中 / 已驳回 / 快照不一致 / 重复审核 /
     * 已通过再提交）。→ 409 */
    public static final ErrorCode SANDBOX_RESULT_STATE_CONFLICT = ErrorCode.of("1009C0016");
    public static final String SANDBOX_RESULT_STATE_CONFLICT_MESSAGE =
            "结果物当前状态不允许该操作";

    /** 沙箱运行环境暂不可用（运行时关闭 / 镜像缺失 / 数据未就绪）。→ 503 */
    public static final ErrorCode SANDBOX_RUNTIME_UNAVAILABLE = ErrorCode.of("1009S0004");
    public static final String SANDBOX_RUNTIME_UNAVAILABLE_MESSAGE = "沙箱运行环境暂不可用";

    /** 沙箱环境操作失败（启动 / 停止 / 执行异常）。→ 500 */
    public static final ErrorCode SANDBOX_OPERATION_FAILED = ErrorCode.of("1009S0005");
    public static final String SANDBOX_OPERATION_FAILED_MESSAGE = "沙箱环境操作失败";

    private SandboxErrorCodes() {
    }
}
