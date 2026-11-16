package com.ctds.common.modelgateway.route;

import com.ctds.common.modelgateway.spi.ModelProviderAdapter;

/** B2 路由判定结果：OK ⇒ adapter 非空；UNKNOWN_PROVIDER ⇒ adapter 为 null（不静默 fallback）。 */
public record RouteDecision(Status status, ModelProviderAdapter adapter) {

    public enum Status {
        OK,
        UNKNOWN_PROVIDER
    }
}
