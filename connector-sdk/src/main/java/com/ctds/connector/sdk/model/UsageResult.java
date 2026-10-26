package com.ctds.connector.sdk.model;

/** 使用执行结果（仅在三态之"放行"时返回——拒绝 / 不可达均以异常终止，无本地放行路径）。 */
public record UsageResult(String deliveryNo, String status, String result) {
}
