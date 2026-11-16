package com.ctds.common.modelgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ctds.common.modelgateway.config.ModelGatewayProperties;
import com.ctds.common.modelgateway.control.ControlDecision;
import com.ctds.common.modelgateway.control.GatewayControl;
import org.junit.jupiter.api.Test;

class GatewayControlTest {

    @Test
    void disabledByDefaultShouldDenyForAnyUsage() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();

        assertEquals(ControlDecision.DENIED_DISABLED, GatewayControl.check(properties, 0L));
        assertEquals(ControlDecision.DENIED_DISABLED, GatewayControl.check(properties, 100L));
        assertEquals(ControlDecision.DENIED_DISABLED, GatewayControl.check(properties, -1L));
    }

    @Test
    void enabledWithZeroBudgetShouldAllow() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);

        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, 0L));
        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, 999999L));
    }

    @Test
    void budgetBoundaryShouldDenyAtAndAboveLimit() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setTokenBudget(100L);

        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, 99L));
        assertEquals(ControlDecision.DENIED_BUDGET_EXCEEDED, GatewayControl.check(properties, 100L));
        assertEquals(ControlDecision.DENIED_BUDGET_EXCEEDED, GatewayControl.check(properties, 101L));
    }

    @Test
    void nonPositiveBudgetShouldMeanUnlimited() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setTokenBudget(0L);
        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, 1000000L));

        properties.setTokenBudget(-1L);
        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, 1000000L));
    }

    @Test
    void negativeUsageShouldBeAllowedWhenEnabledUnderBudget() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setTokenBudget(100L);

        assertEquals(ControlDecision.ALLOW, GatewayControl.check(properties, -1L));
    }

    @Test
    void disabledShouldTakePrecedenceOverBudget() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setEnabled(false);
        properties.setTokenBudget(100L);

        assertEquals(ControlDecision.DENIED_DISABLED, GatewayControl.check(properties, 0L));
    }
}
