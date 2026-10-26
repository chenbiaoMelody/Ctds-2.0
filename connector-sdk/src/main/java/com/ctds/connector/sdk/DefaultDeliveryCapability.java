package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.DeliveryCapability;
import com.ctds.connector.sdk.model.DeliveryRequest;
import com.ctds.connector.sdk.model.DeliveryView;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;

/** 交付门面默认实现（需求方侧发起端——幂等键与凭据头自动组装）。 */
final class DefaultDeliveryCapability implements DeliveryCapability {

    private final PlatformClient client;

    DefaultDeliveryCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public DeliveryView requestDelivery(DeliveryRequest request) {
        DefaultUsageControlCapability.requireCredential(
                request == null ? null : request.contractNo(),
                request == null ? null : request.purpose(),
                request == null ? null : request.territory());
        return client.post("/api/v1/deliveries", request,
                credentialHeaders(request.contractNo(), request.purpose(), request.territory(),
                        request.idempotencyKey()),
                new ParameterizedTypeReference<PlatformResponse<DeliveryView>>() {
                });
    }

    @Override
    public DeliveryView getDelivery(String deliveryNo) {
        DefaultIdentityCapability.requireText(deliveryNo, "deliveryNo");
        return client.get("/api/v1/deliveries/" + deliveryNo,
                new ParameterizedTypeReference<PlatformResponse<DeliveryView>>() {
                });
    }

    /** 凭据三件 + 幂等键（幂等键缺省自动生成——同请求复用）。 */
    static Map<String, String> credentialHeaders(String contractNo, String purpose,
            String territory, String idempotencyKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put(PlatformClient.HEADER_CONTRACT_NO, contractNo);
        headers.put(PlatformClient.HEADER_PURPOSE, purpose);
        headers.put(PlatformClient.HEADER_TERRITORY, territory);
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey;
        headers.put(PlatformClient.HEADER_IDEMPOTENCY_KEY, key);
        return headers;
    }
}
