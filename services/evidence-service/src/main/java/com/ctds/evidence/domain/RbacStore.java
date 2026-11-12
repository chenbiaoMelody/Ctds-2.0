package com.ctds.evidence.domain;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WBS-3.9.2 映射仓储端口（领域层）：应用服务只依赖本接口，
 * 基础设施实现见 infrastructure.JdbcRbacStore（EvidenceArchitectureTest 分层约束）。
 */
public interface RbacStore {

    /** 全量生效映射（R1 读面与值域封闭校验的共同来源）。 */
    Map<String, Set<String>> allRolePermissions();

    /** 授予；重复授予 / 重复变更单号 = 零新增留痕。 */
    boolean grant(String role, String permission, String changeNo,
            String changeOrderNo, String changedBy, Timestamp changedAt);

    /** 收回；未授予 = 零副作用。 */
    boolean revoke(String role, String permission, String changeNo,
            String changeOrderNo, String changedBy, Timestamp changedAt);

    /** 留痕分页（按时间倒序）。 */
    List<RbacChangeView> changes(long offset, int limit);

    /** 留痕总数。 */
    long countChanges();
}
