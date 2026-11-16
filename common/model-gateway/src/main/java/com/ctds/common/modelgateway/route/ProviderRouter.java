package com.ctds.common.modelgateway.route;

import com.ctds.common.modelgateway.config.ModelGatewayProperties;
import com.ctds.common.modelgateway.config.ProviderIds;
import com.ctds.common.modelgateway.spi.ModelProviderAdapter;
import java.util.Map;

/** B2 配置化路由：指定优先于默认；注册表为准，路由不查开关、不校验连接参数（hifi §2/§6）。 */
public final class ProviderRouter {

    private final ModelGatewayProperties properties;
    private final Map<String, ModelProviderAdapter> adapters;

    public ProviderRouter(final ModelGatewayProperties properties,
                          final Map<String, ModelProviderAdapter> adapters) {
        this.properties = properties;
        this.adapters = adapters;
    }

    public RouteDecision route(final String providerId) {
        final ModelProviderAdapter adapter = adapters.get(resolveProviderId(providerId));
        if (adapter == null) {
            return new RouteDecision(RouteDecision.Status.UNKNOWN_PROVIDER, null);
        }
        return new RouteDecision(RouteDecision.Status.OK, adapter);
    }

    private String resolveProviderId(final String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return providerId;
        }
        final String configured = properties.getDefaultProvider();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return ProviderIds.GLM;
    }
}
