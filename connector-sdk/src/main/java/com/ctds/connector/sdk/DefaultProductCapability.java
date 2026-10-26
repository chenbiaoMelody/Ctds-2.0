package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ProductCapability;
import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.PageResult;
import com.ctds.connector.sdk.model.ProductView;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 产品管理门面默认实现（产品上架 / 四形态由目录域提供；查询返回平台分页信封）。 */
final class DefaultProductCapability implements ProductCapability {

    private final PlatformClient client;

    DefaultProductCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public ProductView publishProduct(CreateProductCommand command) {
        if (command == null) {
            throw new ConnectorException(SdkErrorType.CONFIG, "command 不可为空", null);
        }
        return client.post("/api/v1/data-products", command, Map.of(),
                new ParameterizedTypeReference<PlatformResponse<ProductView>>() {
                });
    }

    @Override
    public PageResult<ProductView> listMyProducts(int pageNum, int pageSize) {
        return client.get(uri -> Pages.apply(uri.path("/api/v1/data-products/mine"), pageNum, pageSize).build(),
                Map.of(),
                new ParameterizedTypeReference<PlatformResponse<PageResult<ProductView>>>() {
                });
    }
}
