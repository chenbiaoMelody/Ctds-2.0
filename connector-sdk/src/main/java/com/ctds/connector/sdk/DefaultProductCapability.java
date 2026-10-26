package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ProductCapability;
import com.ctds.connector.sdk.model.CreateProductCommand;
import com.ctds.connector.sdk.model.ProductView;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 产品管理门面默认实现（产品上架 / 四形态由目录域提供）。 */
final class DefaultProductCapability implements ProductCapability {

    private final PlatformClient client;

    DefaultProductCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public ProductView publishProduct(CreateProductCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command 不可为空");
        }
        return client.post("/api/v1/data-products", command, Map.of(),
                new ParameterizedTypeReference<PlatformResponse<ProductView>>() {
                });
    }

    @Override
    public List<ProductView> listMyProducts() {
        return client.get("/api/v1/data-products/mine",
                new ParameterizedTypeReference<PlatformResponse<List<ProductView>>>() {
                });
    }
}
