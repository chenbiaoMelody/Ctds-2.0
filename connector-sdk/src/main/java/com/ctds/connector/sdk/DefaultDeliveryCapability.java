package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.DeliveryCapability;
import com.ctds.connector.sdk.model.DeliveryView;
import com.ctds.connector.sdk.model.FileDeliveryRequest;
import com.ctds.connector.sdk.model.ProductAccessRequest;
import com.ctds.connector.sdk.model.ProductView;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 交付门面默认实现（需求方侧发起端；API 型经网关交付面，凭据头按网关契约编码）。 */
final class DefaultDeliveryCapability implements DeliveryCapability {

    /** 文件型交付形态（平台 DeliveryType 枚举名）。 */
    private static final String DELIVERY_TYPE_FILE = "FILE";

    private final PlatformClient client;

    DefaultDeliveryCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public ProductView requestApiDelivery(ProductAccessRequest request) {
        if (request == null || request.productId() == null || request.productId() <= 0) {
            throw new ConnectorException(SdkErrorType.CONFIG, "productId 必填且为正整数（被交付资源）", null);
        }
        Map<String, String> headers = Credentials.headers(
                request.contractNo(), request.purpose(), request.territory());
        return client.get(
                uri -> uri.path("/api/v1/data-products")
                        .pathSegment(String.valueOf(request.productId())).build(),
                headers,
                new ParameterizedTypeReference<PlatformResponse<ProductView>>() {
                });
    }

    @Override
    public DeliveryView requestFileDelivery(FileDeliveryRequest request) {
        if (request == null) {
            throw new ConnectorException(SdkErrorType.CONFIG, "交付请求不可为空", null);
        }
        String fileId = Credentials.requireFileId(request.fileId());
        String idempotencyKey = Credentials.requireIdempotencyKey(request.idempotencyKey());
        Map<String, String> headers = Credentials.headers(
                request.contractNo(), request.purpose(), request.territory());
        headers.put(PlatformClient.HEADER_IDEMPOTENCY_KEY, idempotencyKey);
        return client.post("/api/v1/deliveries",
                new PlatformFileDeliveryBody(request.contractNo(), DELIVERY_TYPE_FILE, fileId,
                        request.purpose(), request.territory()),
                headers,
                new ParameterizedTypeReference<PlatformResponse<DeliveryView>>() {
                });
    }

    @Override
    public DeliveryView getDelivery(String deliveryNo) {
        DefaultIdentityCapability.requireText(deliveryNo, "deliveryNo");
        return client.get(
                uri -> uri.path("/api/v1/deliveries").pathSegment(deliveryNo).build(),
                Map.of(),
                new ParameterizedTypeReference<PlatformResponse<DeliveryView>>() {
                });
    }

    /** 平台交付域请求体同构（交付域 {@code DeliveryRequest}：契约 / 形态 / 文件 / 策略要素）。 */
    private record PlatformFileDeliveryBody(String contractNo, String deliveryType, String fileId,
            String purpose, String territory) {
    }
}
