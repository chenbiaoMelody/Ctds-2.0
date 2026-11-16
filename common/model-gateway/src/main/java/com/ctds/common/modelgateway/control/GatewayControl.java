package com.ctds.common.modelgateway.control;

import com.ctds.common.modelgateway.config.ModelGatewayProperties;

/** B3 开关/预算纯函数判定：先开关后预算；预算 0/负=未设限，上限含等号拒绝（hifi §2 真理表）。 */
public final class GatewayControl {

    private GatewayControl() {
    }

    public static ControlDecision check(final ModelGatewayProperties properties, final long usedTokens) {
        if (!properties.isEnabled()) {
            return ControlDecision.DENIED_DISABLED;
        }
        final long budget = properties.getTokenBudget();
        if (budget > 0 && usedTokens >= budget) {
            return ControlDecision.DENIED_BUDGET_EXCEEDED;
        }
        return ControlDecision.ALLOW;
    }
}
