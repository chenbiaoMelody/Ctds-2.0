package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.UsageControlCapability;
import com.ctds.connector.sdk.model.ProductAccessRequest;
import com.ctds.connector.sdk.model.ProductView;
import com.ctds.connector.sdk.model.UsageRequest;
import com.ctds.connector.sdk.model.UsageResult;
import com.ctds.connector.sdk.model.UsageSummaryView;
import org.springframework.core.ParameterizedTypeReference;

/**
 * 使用控制门面默认实现（行为 5 / Q6-A）：判定唯一权威在平台——经网关交付面请求被交付资源，
 * 并接受平台判定结果（拒绝透传不重试 / 不可达 fail-closed），无本地放行路径。
 */
final class DefaultUsageControlCapability implements UsageControlCapability {

    private final PlatformClient client;

    private final DefaultDeliveryCapability delivery;

    DefaultUsageControlCapability(PlatformClient client, DefaultDeliveryCapability delivery) {
        this.client = client;
        this.delivery = delivery;
    }

    @Override
    public UsageResult executeUsage(UsageRequest request) {
        if (request == null || request.productId() == null) {
            throw new ConnectorException(SdkErrorType.CONFIG, "productId 必填（被交付资源）", null);
        }
        Credentials.requireContractNo(request.contractNo());
        ProductView resource = delivery.requestApiDelivery(new ProductAccessRequest(
                request.productId(), request.contractNo(), request.purpose(), request.territory()));
        return new UsageResult(request.productId(), resource);
    }

    @Override
    public UsageSummaryView getUsageSummary(String contractNo) {
        DefaultIdentityCapability.requireText(contractNo, "contractNo");
        return client.get(
                uri -> uri.path("/api/v1/contracts").pathSegment(contractNo)
                        .path("usage-summary").build(),
                java.util.Map.of(),
                new ParameterizedTypeReference<PlatformResponse<UsageSummaryView>>() {
                });
    }
}
