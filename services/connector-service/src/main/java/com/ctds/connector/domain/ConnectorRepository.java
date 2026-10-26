package com.ctds.connector.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 注册申请 / 档案仓储（domain 只表达业务语义，分页以 offset/limit 表达——domain 不依赖 common-pagination）。
 */
public interface ConnectorRepository {

    /** 申请单号取号（CA+6 位，单行原子）。 */
    String nextApplicationNo();

    /** 连接器编号取号（CN+6 位，核验通过时调用）。 */
    String nextConnectorNo();

    /** 建档（仅业务列；归一化名称由库生成列承载）。 */
    void insert(ConnectorRegistration registration);

    Optional<ConnectorRegistration> findByApplicationNo(String applicationNo);

    Optional<ConnectorRegistration> findByConnectorNo(String connectorNo);

    /** 同主体同名活跃档案存在性（status != CANCELLED；excludeApplicationNo 用于补正时排除自身）。 */
    boolean existsActiveName(String subjectNo, String connectorName, String excludeApplicationNo);

    /** 补正要素（仅待核验态，返回受影响行数供并发门槛判断）。 */
    int amend(String applicationNo, String connectorName, String connectorType, String connectorVersion);

    /** 核验通过：写编号 + 转已注册（带 status 乐观门槛，返回受影响行数）。 */
    int markRegistered(String applicationNo, String connectorNo);

    /** 核验拒绝：记录最近拒绝码与理由（状态保持待核验）。 */
    void markVerificationRejected(String applicationNo, String reasonCode, String reason);

    /** 心跳刷新（仅已注册态，返回受影响行数）。 */
    int touchHeartbeat(String connectorNo, LocalDateTime now);

    /** 版本替换（带"已注册态 + 版本未变"乐观门槛，返回受影响行数；3.6.4 升级）。 */
    int updateVersion(String connectorNo, String fromVersion, String toVersion);

    /** 注销（待核验或已注册 → 已注销 + cancelled_at，返回受影响行数；3.6.4）。 */
    int cancel(String connectorNo, LocalDateTime now);

    /** 撤回待核验申请（仅 PENDING_REVIEW → CANCELLED + cancelled_at，返回受影响行数；3.6.4）。 */
    int withdraw(String applicationNo, LocalDateTime now);

    /** 列表（subjectNo / status 为 null = 不筛选）；申请时间倒序。 */
    List<ConnectorRegistration> findRegistrations(String subjectNo, ConnectorStatus status,
            long offset, int limit);

    /** 待核验队列（固定 PENDING_REVIEW，申请时间**升序**——先到先审）。 */
    List<ConnectorRegistration> findPendingQueue(long offset, int limit);

    /** 总数（与 findRegistrations 同过滤口径）。 */
    long countRegistrations(String subjectNo, ConnectorStatus status);
}
