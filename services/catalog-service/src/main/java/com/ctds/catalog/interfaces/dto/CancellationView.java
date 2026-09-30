package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.Dataset;

/**
 * 注销结果视图（WBS-3.3.2 hifi §1.1 W3）：datasetId / dataNo / status / cancelled。
 * cancelled 恒 true（接口返回成功即注销两写事务已提交；不可逆由状态机表达）。
 */
public record CancellationView(long datasetId, String dataNo, String status, boolean cancelled) {

    /** 由注销后的资源状态映射视图（status = DELETED）。 */
    public static CancellationView from(final Dataset dataset) {
        return new CancellationView(dataset.id(), dataset.dataNo(), dataset.status().name(), true);
    }
}
