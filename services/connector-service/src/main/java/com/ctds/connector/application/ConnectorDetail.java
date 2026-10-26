package com.ctds.connector.application;

import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorRegistration;

/**
 * 连接器详情快照：档案 + 绑定（可空）+ **实时** DID 状态（可空：列表不查 DID，避免 N 次跨域调用）+ 在线派生。
 */
public record ConnectorDetail(
        ConnectorRegistration registration,
        ConnectorBinding binding,
        String didStatus,
        boolean verified,
        boolean online) {
}
