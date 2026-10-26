package com.ctds.connector.sdk.spi;

/**
 * 连通性自检结果（规格行为 13）：只读探测（连通 / 凭据 / 权限三查），
 * 不产生源端数据变更；自检通过不等于业务授权（读取仍受"先判定后取数"约束）。
 *
 * @param passed      是否通过
 * @param failureType 失败原因（通过时为 null）
 * @param message     业务可读说明
 */
public record SourceCheckResult(boolean passed, SourceCheckFailureType failureType, String message) {

    /** 通过。 */
    public static SourceCheckResult ok() {
        return new SourceCheckResult(true, null, "连通 / 凭据 / 权限三查通过");
    }

    /** 失败（三类原因之一，业务可读）。 */
    public static SourceCheckResult fail(SourceCheckFailureType failureType, String message) {
        return new SourceCheckResult(false, failureType, message);
    }
}
