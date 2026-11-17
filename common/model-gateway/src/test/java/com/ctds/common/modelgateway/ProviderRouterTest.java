package com.ctds.common.modelgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ctds.common.modelgateway.api.ChatCompletionRequest;
import com.ctds.common.modelgateway.api.ChatCompletionResponse;
import com.ctds.common.modelgateway.config.ModelGatewayProperties;
import com.ctds.common.modelgateway.config.ProviderIds;
import com.ctds.common.modelgateway.route.ProviderRouter;
import com.ctds.common.modelgateway.route.RouteDecision;
import com.ctds.common.modelgateway.spi.ModelProviderAdapter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRouterTest {

    private static ModelProviderAdapter stub(final String providerId) {
        return new ModelProviderAdapter() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public ChatCompletionResponse chat(final ChatCompletionRequest request) {
                throw new UnsupportedOperationException("V1.0 桩适配器不承载真实调用");
            }
        };
    }

    @Test
    void routeShouldUseDefaultGlmWhenProviderIdNullOrBlank() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        final ModelProviderAdapter glm = stub(ProviderIds.GLM);
        final ProviderRouter router = new ProviderRouter(properties, Map.of(ProviderIds.GLM, glm));

        for (final String providerId : new String[] {null, "", "   "}) {
            final RouteDecision decision = router.route(providerId);
            assertEquals(RouteDecision.Status.OK, decision.status());
            assertSame(glm, decision.adapter());
            assertEquals("glm", decision.adapter().providerId());
        }
    }

    @Test
    void blankDefaultProviderConfigShouldFallBackToGlmConstant() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setDefaultProvider("   ");
        final ModelProviderAdapter glm = stub(ProviderIds.GLM);
        final ProviderRouter router = new ProviderRouter(properties, Map.of(ProviderIds.GLM, glm));

        final RouteDecision decision = router.route(null);

        assertEquals(RouteDecision.Status.OK, decision.status());
        assertSame(glm, decision.adapter());
    }

    @Test
    void nullDefaultProviderConfigShouldFallBackToGlmConstant() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setDefaultProvider(null);
        final ModelProviderAdapter glm = stub(ProviderIds.GLM);
        final ProviderRouter router = new ProviderRouter(properties, Map.of(ProviderIds.GLM, glm));

        final RouteDecision decision = router.route(null);

        assertEquals(RouteDecision.Status.OK, decision.status());
        assertSame(glm, decision.adapter());
    }

    @Test
    void routeShouldFollowDefaultProviderConfigWithoutCodeChange() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        final ModelProviderAdapter deepseek = stub(ProviderIds.DEEPSEEK);
        final ModelProviderAdapter qwen = stub(ProviderIds.QWEN);
        final ModelProviderAdapter kimi = stub(ProviderIds.KIMI);
        final ProviderRouter router = new ProviderRouter(properties,
                Map.of(ProviderIds.DEEPSEEK, deepseek, ProviderIds.QWEN, qwen, ProviderIds.KIMI, kimi));

        properties.setDefaultProvider(ProviderIds.DEEPSEEK);
        final RouteDecision deepseekDecision = router.route(null);
        assertEquals(RouteDecision.Status.OK, deepseekDecision.status());
        assertSame(deepseek, deepseekDecision.adapter());
        assertEquals("deepseek", deepseekDecision.adapter().providerId());

        properties.setDefaultProvider(ProviderIds.QWEN);
        final RouteDecision qwenDecision = router.route(null);
        assertSame(qwen, qwenDecision.adapter());
        assertEquals("qwen", qwenDecision.adapter().providerId());

        properties.setDefaultProvider(ProviderIds.KIMI);
        final RouteDecision kimiDecision = router.route(null);
        assertSame(kimi, kimiDecision.adapter());
        assertEquals("kimi", kimiDecision.adapter().providerId());
    }

    @Test
    void explicitProviderIdShouldOverrideDefault() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        final ModelGatewayProperties.ProviderConfig qwenConfig = new ModelGatewayProperties.ProviderConfig();
        qwenConfig.setBaseUrl("https://placeholder.invalid/v1");
        qwenConfig.setApiKey("placeholder-not-a-real-key");
        qwenConfig.setModel("placeholder-model");
        properties.getProviders().put(ProviderIds.QWEN, qwenConfig);
        final ModelProviderAdapter glm = stub(ProviderIds.GLM);
        final ModelProviderAdapter qwen = stub(ProviderIds.QWEN);
        final ProviderRouter router = new ProviderRouter(properties,
                Map.of(ProviderIds.GLM, glm, ProviderIds.QWEN, qwen));

        final RouteDecision decision = router.route(ProviderIds.QWEN);

        assertEquals(RouteDecision.Status.OK, decision.status());
        assertSame(qwen, decision.adapter());
    }

    @Test
    void providerConnectionConfigShouldRoundTripThroughBeanAccessors() {
        assertEquals("ctds.ai.gateway", ModelGatewayProperties.PREFIX);
        assertEquals("glm", ProviderIds.GLM);
        assertEquals("deepseek", ProviderIds.DEEPSEEK);
        assertEquals("qwen", ProviderIds.QWEN);
        assertEquals("kimi", ProviderIds.KIMI);
        final ModelGatewayProperties.ProviderConfig config = new ModelGatewayProperties.ProviderConfig();
        config.setBaseUrl("https://placeholder.invalid/v1");
        config.setApiKey("placeholder-not-a-real-key");
        config.setModel("placeholder-model");
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        properties.setProviders(Map.of(ProviderIds.GLM, config));

        final ModelGatewayProperties.ProviderConfig bound = properties.getProviders().get(ProviderIds.GLM);
        assertEquals("https://placeholder.invalid/v1", bound.getBaseUrl());
        assertEquals("placeholder-not-a-real-key", bound.getApiKey());
        assertEquals("placeholder-model", bound.getModel());
    }

    @Test
    void unknownExplicitProviderShouldBeRejectedWithoutFallback() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        final ProviderRouter router = new ProviderRouter(properties,
                Map.of(ProviderIds.GLM, stub(ProviderIds.GLM)));

        final RouteDecision decision = router.route("unknown");

        assertEquals(RouteDecision.Status.UNKNOWN_PROVIDER, decision.status());
        assertNull(decision.adapter());
    }

    @Test
    void unregisteredDefaultProviderShouldBeRejectedWithoutFallback() {
        final ModelGatewayProperties properties = new ModelGatewayProperties();
        final ProviderRouter router = new ProviderRouter(properties,
                Map.of(ProviderIds.DEEPSEEK, stub(ProviderIds.DEEPSEEK)));

        final RouteDecision decision = router.route(null);

        assertEquals(RouteDecision.Status.UNKNOWN_PROVIDER, decision.status());
        assertNull(decision.adapter());
    }
}
