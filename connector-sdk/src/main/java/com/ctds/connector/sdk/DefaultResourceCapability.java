package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ResourceCapability;
import com.ctds.connector.sdk.model.DatasetView;
import com.ctds.connector.sdk.model.RegisterDatasetCommand;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 资源管理门面默认实现（资源登记与元数据由空间与目录域提供）。 */
final class DefaultResourceCapability implements ResourceCapability {

    private final PlatformClient client;

    DefaultResourceCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public DatasetView registerDataset(String spaceId, RegisterDatasetCommand command) {
        DefaultIdentityCapability.requireText(spaceId, "spaceId");
        if (command == null) {
            throw new IllegalArgumentException("command 不可为空");
        }
        return client.post("/api/v1/data-spaces/" + spaceId + "/datasets", command, Map.of(),
                new ParameterizedTypeReference<PlatformResponse<DatasetView>>() {
                });
    }

    @Override
    public List<DatasetView> listMyDatasets() {
        return client.get("/api/v1/datasets/mine",
                new ParameterizedTypeReference<PlatformResponse<List<DatasetView>>>() {
                });
    }
}
