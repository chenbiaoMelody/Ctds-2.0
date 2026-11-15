package com.ctds.connector.application;

import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorRegistration;

/**
 * 连接器详情快照：档案 + 绑定（可空）+ **实时** DID 状态（可空：列表不查 DID，避免 N 次跨域调用）+ 在线派生。
 * WBS-3.9.4 加性：healthStatus 三态显式值（B6-1），既有 online 布尔语义零变化。
 */
public record ConnectorDetail(
        ConnectorRegistration registration,
        ConnectorBinding binding,
        String didStatus,
        boolean verified,
        boolean online,
        String healthStatus) {
}
