package com.ctds.common.modelgateway.control;

/** B3 判定结果三态（拒绝返回枚举，不抛异常、不出错误码——错误码映射归消费方）。 */
public enum ControlDecision {
    ALLOW,
    DENIED_DISABLED,
    DENIED_BUDGET_EXCEEDED
}
