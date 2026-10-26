package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.DatasetView;
import com.ctds.connector.sdk.model.PageResult;
import com.ctds.connector.sdk.model.RegisterDatasetCommand;

/**
 * 资源管理门面（规格行为 2 资源腿）：资源登记与元数据由空间与目录域提供——SDK 只封装。
 */
public interface ResourceCapability {

    /** 登记数据集（归属指定数据空间）。 */
    DatasetView registerDataset(String spaceId, RegisterDatasetCommand command);

    /** 查我的数据集（平台分页信封；pageNum ≥1，pageSize 1~100）。 */
    PageResult<DatasetView> listMyDatasets(int pageNum, int pageSize);
}
