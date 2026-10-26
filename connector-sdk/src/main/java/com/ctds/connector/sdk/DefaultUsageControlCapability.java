package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.UsageControlCapability;
import com.ctds.connector.sdk.model.DeliveryRequest;
import com.ctds.connector.sdk.model.DeliveryView;
import com.ctds.connector.sdk.model.UsageRequest;
import com.ctds.connector.sdk.model.UsageResult;
import com.ctds.connector.sdk.model.UsageSummaryView;
import org.springframework.core.ParameterizedTypeReference;

/**
 * 使用控制门面默认实现（行为 5 / Q6-A）：判定唯一权威在平台——本实现只
 * 凭据发起 + 接受判定结果（拒绝透传不重试 / 不可达 fail-closed），无本地放行路径。
 */
final class DefaultUsageControlCapability implements UsageControlCapability {

    private final PlatformClient client;

    DefaultUsageControlCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public UsageResult executeUsage(UsageRequest request) {
        requireCredential(request == null ? null : request.contractNo(),
                request == null ? null : request.purpose(),
                request == null ? null : request.territory());
        DeliveryRequest delivery = new DeliveryRequest(request.contractNo(), "API", null,
                request.purpose(), request.territory(), request.idempotencyKey());
        DeliveryView view = client.post("/api/v1/deliveries", delivery,
                DefaultDeliveryCapability.credentialHeaders(request.contractNo(), request.purpose(),
                        request.territory(), request.idempotencyKey()),
                new ParameterizedTypeReference<PlatformResponse<DeliveryView>>() {
                });
        return new UsageResult(view.deliveryNo(), view.status(), view.result());
    }

    @Override
    public UsageSummaryView getUsageSummary(String contractNo) {
        DefaultIdentityCapability.requireText(contractNo, "contractNo");
        return client.get("/api/v1/contracts/" + contractNo + "/usage-summary",
                new ParameterizedTypeReference<PlatformResponse<UsageSummaryView>>() {
                });
    }

    /** 凭据三件必填校验（CONFIG 分类 fail-fast，不发起平台调用）。 */
    static void requireCredential(String contractNo, String purpose, String territory) {
        if (contractNo == null || contractNo.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "contractNo 必填（凭据三件之一）", null);
        }
        if (purpose == null || purpose.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "purpose 必填（凭据三件之二）", null);
        }
        if (territory == null || territory.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "territory 必填（凭据三件之三）", null);
        }
    }
}
