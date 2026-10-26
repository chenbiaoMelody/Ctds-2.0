package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ContractCapability;
import com.ctds.connector.sdk.capability.DeliveryCapability;
import com.ctds.connector.sdk.capability.IdentityCapability;
import com.ctds.connector.sdk.capability.ProductCapability;
import com.ctds.connector.sdk.capability.ResourceCapability;
import com.ctds.connector.sdk.capability.UsageControlCapability;
import java.time.Duration;

/**
 * 连接器 SDK 入口门面（WBS-3.6.2 骨架 / ADR-026）：参与方业务系统引入本库，
 * 经六个能力门面（身份 / 资源 / 产品 / 合约 / 交付 / 使用控制）发起六类业务动作
 * ——业务能力全部由平台对应域提供（规格行为 2 规则 1 映射表），零第二业务口径。
 *
 * <p>builder 必填三件：baseUrl（单一平台入口，演示期 = 网关）/ tokenProvider（Bearer 令牌）/
 * context（身份锚）；缺失即构建 fail-fast（CONFIG 分类，不静默降级）。</p>
 */
public final class ConnectorSdk {

    private final ConnectorContext context;

    private final IdentityCapability identity;

    private final ResourceCapability resource;

    private final ProductCapability product;

    private final ContractCapability contract;

    private final DeliveryCapability delivery;

    private final UsageControlCapability usageControl;

    private ConnectorSdk(PlatformClient client, ConnectorContext context) {
        this.context = context;
        this.identity = new DefaultIdentityCapability(client);
        this.resource = new DefaultResourceCapability(client);
        this.product = new DefaultProductCapability(client);
        this.contract = new DefaultContractCapability(client);
        DefaultDeliveryCapability deliveryCapability = new DefaultDeliveryCapability(client);
        this.delivery = deliveryCapability;
        this.usageControl = new DefaultUsageControlCapability(client, deliveryCapability);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 身份锚元数据（connectorNo 骨架期可空占位，3.6.3 签发后注入）。 */
    public ConnectorContext context() {
        return context;
    }

    /** 身份管理门面（行为 3）。 */
    public IdentityCapability identity() {
        return identity;
    }

    /** 资源管理门面（行为 2 资源腿）。 */
    public ResourceCapability resource() {
        return resource;
    }

    /** 产品管理门面（行为 2 产品腿）。 */
    public ProductCapability product() {
        return product;
    }

    /** 合约管理门面（行为 2 合约腿）。 */
    public ContractCapability contract() {
        return contract;
    }

    /** 交付门面（行为 4：需求方侧发起端，幂等键自动）。 */
    public DeliveryCapability delivery() {
        return delivery;
    }

    /** 使用控制门面（行为 5：消费判定结果 + fail-closed 三态）。 */
    public UsageControlCapability usageControl() {
        return usageControl;
    }

    /** 构建器（必填三件 fail-fast；超时可配，默认连接 2s / 读 10s）。 */
    public static final class Builder {

        private String baseUrl;

        private TokenProvider tokenProvider;

        private ConnectorContext context;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(10);

        private Builder() {
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder tokenProvider(TokenProvider value) {
            this.tokenProvider = value;
            return this;
        }

        public Builder context(ConnectorContext value) {
            this.context = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Builder readTimeout(Duration value) {
            this.readTimeout = value;
            return this;
        }

        public ConnectorSdk build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new ConnectorException(SdkErrorType.CONFIG, "baseUrl 必填（单一平台入口，演示期 = 网关）", null);
            }
            if (tokenProvider == null) {
                throw new ConnectorException(SdkErrorType.CONFIG, "tokenProvider 必填（Bearer 令牌来源）", null);
            }
            if (context == null) {
                throw new ConnectorException(SdkErrorType.CONFIG, "context 必填（身份锚：主体 DID + 连接器编号占位）", null);
            }
            PlatformClient client = PlatformClient.create(baseUrl, tokenProvider, connectTimeout, readTimeout);
            return new ConnectorSdk(client, context);
        }
    }
}
