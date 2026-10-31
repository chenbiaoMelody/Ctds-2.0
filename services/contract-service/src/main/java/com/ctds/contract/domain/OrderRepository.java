package com.ctds.contract.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储端口（WBS-3.7.3 hifi §3.1）。事务口径：create / completeByContract / closeByContract
 * 均在调用方事务内执行（创建 = OrderCommandService 事务模板；状态联动 = ContractOrderSyncService
 * @Transactional——与合约写面原子提交，hifi §3.2/§3.3）；insertLog 为独立写入（无事务上下文时
 * 自动提交，拒绝留痕主链回滚不影响——沿 JdbcContractRepository.insertLog 契约）。
 */
public interface OrderRepository {

    /** 订单编号取号（product_order_no_seq 全局 1 行原子自增；调用方事务内执行）。 */
    int nextOrderNoSeq();

    /** 订单落库 + CREATE 留痕（同事务；调用方事务内）。 */
    void create(Order order, OrderActionLog createLog);

    /** 按订单号读（详情；不存在返回 empty）。 */
    Optional<Order> findByNo(String orderNo);

    /** 进行中订单预检查（B3-2：同需方同产品 active 行；无则 empty）。 */
    Optional<Order> findActive(String requesterSubjectNo, long productId);

    /** 关联合约生效 → 订单完成（B3-4 联动一；条件 status='IN_PROGRESS'，返回影响行数，
     * 命中才写 COMPLETE 留痕——幂等：重复同步不产生第二条留痕）。 */
    int completeByContract(String contractNo, LocalDateTime completedAt, String operatorNo);

    /** 关联合约生效前终止 → 订单关闭（B3-4 联动二；条件 status='IN_PROGRESS'，返回影响行数，
     * 命中才写 CLOSE 留痕；reason 为业务常量文案）。 */
    int closeByContract(String contractNo, String reason, LocalDateTime closedAt,
            String operatorNo);

    /** 本方订单分页（B4-1/B12-1：需方或供方；创建时间倒序）。 */
    PageResult<Order> pageMine(String subjectNo, OrderStatus status, Long productId,
            String contractNo, PageQuery page);

    /** 运营方全量分页检索（B4-2；不做归属过滤；界面归 3.7.7）。 */
    PageResult<Order> pageGovernance(OrderStatus status, Long productId, String contractNo,
            String requesterNo, PageQuery page);

    /** 插入留痕行（独立写入——拒绝留痕场景无外层事务）。 */
    void insertLog(OrderActionLog log);

    /** 订单留痕时间线（按时间正序；含 DENIED_ACCESS，用户读面过滤在应用服务）。 */
    List<OrderActionLog> findLogs(String orderNo);
}
