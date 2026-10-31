package com.ctds.contract.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.Order;
import com.ctds.contract.domain.OrderAction;
import com.ctds.contract.domain.OrderActionLog;
import com.ctds.contract.domain.OrderRepository;
import com.ctds.contract.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 订单仓储（JdbcClient，V4 三表；沿 JdbcContractRepository 先例）。事务口径：create /
 * completeByContract / closeByContract 在调用方事务内执行（创建 = OrderCommandService 事务
 * 模板；状态联动 = ContractOrderSyncService @Transactional——与合约写面原子提交）；insertLog
 * 为独立写入（拒绝留痕无外层事务时自动提交）。状态联动条件更新：仅 status='IN_PROGRESS' 行
 * 命中才转移并写留痕（幂等——重复同步不产生第二条留痕，hifi §3.3）；uk_active_order_key
 * 生成列唯一索引兜底并发（B3-2）。
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private static final String ORDER_COLUMNS = "id, order_no, product_id, product_name, "
            + "provider_subject_no, requester_subject_no, pricing_model, price_amount, "
            + "template_no, template_version_no, contract_no, status, closed_reason, closed_at, "
            + "created_by, created_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcOrderRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ==== 编号取号与创建 ====

    @Override
    public int nextOrderNoSeq() {
        // 全局 1 行原子自增（LAST_INSERT_ID 连接级技巧——调用方事务内共用同一连接，沿先例）
        jdbc.sql("UPDATE product_order_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    @Override
    public void create(final Order order, final OrderActionLog createLog) {
        jdbc.sql("INSERT INTO product_order (order_no, product_id, product_name, "
                        + "provider_subject_no, requester_subject_no, pricing_model, price_amount, "
                        + "template_no, template_version_no, contract_no, status, created_by, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(order.orderNo()).param(order.productId()).param(order.productName())
                .param(order.providerSubjectNo()).param(order.requesterSubjectNo())
                .param(order.pricingModel()).param(order.priceAmount()).param(order.templateNo())
                .param(order.templateVersionNo()).param(order.contractNo())
                .param(order.status().name()).param(order.createdBy())
                .param(Timestamp.valueOf(order.createdAt()))
                .param(Timestamp.valueOf(order.updatedAt())).update();
        insertLog(createLog);
    }

    // ==== 读取 ====

    @Override
    public Optional<Order> findByNo(final String orderNo) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM product_order WHERE order_no = ?")
                .param(orderNo)
                .query((rs, rowNum) -> mapOrder(rs))
                .optional();
    }

    @Override
    public Optional<Order> findActive(final String requesterSubjectNo, final long productId) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM product_order "
                        + "WHERE requester_subject_no = ? AND product_id = ? AND status = 'IN_PROGRESS'")
                .param(requesterSubjectNo).param(productId)
                .query((rs, rowNum) -> mapOrder(rs))
                .optional();
    }

    // ==== 状态联动（B3-4：条件更新 + 命中才写留痕）====

    @Override
    public int completeByContract(final String contractNo, final LocalDateTime completedAt,
            final String operatorNo) {
        final int moved = jdbc.sql("UPDATE product_order SET status = 'COMPLETED', "
                        + "updated_at = ? WHERE contract_no = ? AND status = 'IN_PROGRESS'")
                .param(Timestamp.valueOf(completedAt)).param(contractNo).update();
        if (moved == 1) {
            insertLog(new OrderActionLog(null, orderNoByContract(contractNo), OrderAction.COMPLETE,
                    OrderStatus.IN_PROGRESS.name(), OrderStatus.COMPLETED.name(), null,
                    operatorNo, completedAt));
        }
        return moved;
    }

    @Override
    public int closeByContract(final String contractNo, final String reason,
            final LocalDateTime closedAt, final String operatorNo) {
        final int moved = jdbc.sql("UPDATE product_order SET status = 'CLOSED', "
                        + "closed_reason = ?, closed_at = ?, updated_at = ? "
                        + "WHERE contract_no = ? AND status = 'IN_PROGRESS'")
                .param(reason).param(Timestamp.valueOf(closedAt))
                .param(Timestamp.valueOf(closedAt)).param(contractNo).update();
        if (moved == 1) {
            insertLog(new OrderActionLog(null, orderNoByContract(contractNo), OrderAction.CLOSE,
                    OrderStatus.IN_PROGRESS.name(), OrderStatus.CLOSED.name(), reason,
                    operatorNo, closedAt));
        }
        return moved;
    }

    // ==== 分页（B4-1/B12-1 本方双角色 + B4-2 治理全量）====

    @Override
    public PageResult<Order> pageMine(final String subjectNo, final OrderStatus status,
            final Long productId, final String contractNo, final PageQuery page) {
        final StringBuilder where = new StringBuilder(
                "(provider_subject_no = ? OR requester_subject_no = ?)");
        final List<Object> params = new ArrayList<>(List.of(subjectNo, subjectNo));
        appendFilters(where, params, status, productId, contractNo, null);
        return pageOrders(where.toString(), params, page);
    }

    @Override
    public PageResult<Order> pageGovernance(final OrderStatus status, final Long productId,
            final String contractNo, final String requesterNo, final PageQuery page) {
        final StringBuilder where = new StringBuilder("1 = 1");
        final List<Object> params = new ArrayList<>();
        appendFilters(where, params, status, productId, contractNo, requesterNo);
        return pageOrders(where.toString(), params, page);
    }

    // ==== 留痕 ====

    @Override
    public void insertLog(final OrderActionLog log) {
        jdbc.sql("INSERT INTO product_order_action_log (order_no, action, from_status, "
                        + "to_status, reason, operator_subject_no, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(log.orderNo()).param(log.action().name()).param(log.fromStatus())
                .param(log.toStatus()).param(log.reason()).param(log.operatorSubjectNo())
                .param(Timestamp.valueOf(log.createdAt())).update();
    }

    @Override
    public List<OrderActionLog> findLogs(final String orderNo) {
        return jdbc.sql("SELECT id, order_no, action, from_status, to_status, reason, "
                        + "operator_subject_no, created_at FROM product_order_action_log "
                        + "WHERE order_no = ? ORDER BY created_at ASC, id ASC")
                .param(orderNo)
                .query((rs, rowNum) -> mapLog(rs))
                .list();
    }

    // ==== 私有 ====

    private void appendFilters(final StringBuilder where, final List<Object> params,
            final OrderStatus status, final Long productId, final String contractNo,
            final String requesterNo) {
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        if (productId != null) {
            where.append(" AND product_id = ?");
            params.add(productId);
        }
        if (contractNo != null) {
            where.append(" AND contract_no = ?");
            params.add(contractNo);
        }
        if (requesterNo != null) {
            where.append(" AND requester_subject_no = ?");
            params.add(requesterNo);
        }
    }

    private PageResult<Order> pageOrders(final String where, final List<Object> params,
            final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM product_order WHERE " + where)
                .params(params).query(Long.class).single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<Order> list = jdbc.sql("SELECT " + ORDER_COLUMNS
                        + " FROM product_order WHERE " + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapOrder(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    /** 状态联动留痕的订单号回查（contract_no → order_no；订单行刚在事务内更新，同库可见）。 */
    private String orderNoByContract(final String contractNo) {
        return jdbc.sql("SELECT order_no FROM product_order WHERE contract_no = ?")
                .param(contractNo).query(String.class).optional().orElse(null);
    }

    private Order mapOrder(final ResultSet rs) throws SQLException {
        final Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Order(rs.getLong("id"), rs.getString("order_no"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getString("provider_subject_no"),
                rs.getString("requester_subject_no"), rs.getString("pricing_model"),
                rs.getBigDecimal("price_amount"), rs.getString("template_no"),
                rs.getInt("template_version_no"), rs.getString("contract_no"),
                OrderStatus.valueOf(rs.getString("status")), rs.getString("closed_reason"),
                closedAt == null ? null : closedAt.toLocalDateTime(), rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private OrderActionLog mapLog(final ResultSet rs) throws SQLException {
        return new OrderActionLog(rs.getLong("id"), rs.getString("order_no"),
                OrderAction.valueOf(rs.getString("action")), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"),
                rs.getString("operator_subject_no"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
