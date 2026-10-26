package com.ctds.connector.application;

import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.ConnectorVersionChange;
import java.util.List;

/**
 * 生命周期聚合读快照（hifi §5 #6）：档案 + 绑定（含已解除，记录保留）+ 在途作业数 + 待确认标记 +
 * 版本历史链 + 在线派生（复用 3.6.3 同一派生函数）。**不查 DID 域**（零跨域调用）。
 */
public record LifecycleSnapshot(
        ConnectorRegistration registration,
        ConnectorBinding binding,
        long inProgressJobs,
        boolean pendingUpgrade,
        boolean online,
        List<ConnectorVersionChange> versionHistory) {
}
