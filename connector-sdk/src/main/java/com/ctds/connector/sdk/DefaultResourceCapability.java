package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.ResourceCapability;
import com.ctds.connector.sdk.model.DatasetView;
import com.ctds.connector.sdk.model.PageResult;
import com.ctds.connector.sdk.model.RegisterDatasetCommand;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 资源管理门面默认实现（资源登记与元数据由空间与目录域提供；查询返回平台分页信封）。 */
final class DefaultResourceCapability implements ResourceCapability {

    private final PlatformClient client;

    DefaultResourceCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public DatasetView registerDataset(String spaceId, RegisterDatasetCommand command) {
        DefaultIdentityCapability.requireText(spaceId, "spaceId");
        if (command == null) {
            throw new ConnectorException(SdkErrorType.CONFIG, "command 不可为空", null);
        }
        return client.post(
                uri -> uri.path("/api/v1/data-spaces").pathSegment(spaceId).path("datasets").build(),
                command, Map.of(),
                new ParameterizedTypeReference<PlatformResponse<DatasetView>>() {
                });
    }

    @Override
    public PageResult<DatasetView> listMyDatasets(int pageNum, int pageSize) {
        return client.get(uri -> Pages.apply(uri.path("/api/v1/datasets/mine"), pageNum, pageSize).build(),
                Map.of(),
                new ParameterizedTypeReference<PlatformResponse<PageResult<DatasetView>>>() {
                });
    }
}
