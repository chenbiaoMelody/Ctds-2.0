package com.ctds.contract.application;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.Order;
import com.ctds.contract.domain.OrderAction;
import com.ctds.contract.domain.OrderActionLog;
import com.ctds.contract.domain.OrderErrorCodes;
import com.ctds.contract.domain.OrderRepository;
import com.ctds.contract.domain.OrderStatus;
import com.ctds.contract.domain.PartyRole;
import com.ctds.contract.infrastructure.ContractStatusReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订单读面应用服务（WBS-3.7.3 hifi §3.4；规格 B4-1 本方列表详情 / B4-2 运营全量 /
 * B4-3 留痕时间线 / B4-4 防枚举 / B12-1 双角色读）。
 *
 * <p>防枚举（B4-4）：详情不存在与越权<b>同码同文</b> 1011C0002——不存在仅应用日志
 * （不伪造订单行），越权插 DENIED_ACCESS 拒绝留痕（独立写入，主链无事务上下文——
 * 沿 JdbcContractRepository.insertLog 契约）。用户读面不返回 DENIED_ACCESS 行
 * （拒绝留痕仅供治理与审计，hifi §1.4 注）。</p>
 */
@Service
public class OrderQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);

    private final OrderRepository orderRepository;
    private final ContractStatusReader contractStatusReader;
    private final Clock clock;

    public OrderQueryService(final OrderRepository orderRepository,
            final ContractStatusReader contractStatusReader, final Clock clock) {
        this.orderRepository = orderRepository;
        this.contractStatusReader = contractStatusReader;
        this.clock = clock;
    }

    /** 列表行（R-OR1/R-OR3；myRole 仅 mine 面标注——hifi §1.4）。 */
    public record OrderSummary(String orderNo, String status, String contractNo,
            String contractStatus, long productId, String productName, String providerSubjectNo,
            String requesterSubjectNo, String pricingModel, String priceAmount,
            LocalDateTime createdAt, PartyRole myRole) {
    }

    /** 详情行（R-OR2 = Summary + 模板快照 + 关闭信息 + 留痕时间线）。 */
    public record OrderDetail(String orderNo, String status, String contractNo,
            String contractStatus, long productId, String productName, String providerSubjectNo,
            String requesterSubjectNo, String pricingModel, String priceAmount,
            LocalDateTime createdAt, PartyRole myRole, String templateNo, int templateVersionNo,
            String closedReason, LocalDateTime closedAt, List<OrderActionView> actions) {
    }

    /** 留痕时间线行（用户读面已过滤 DENIED_ACCESS）。 */
    public record OrderActionView(OrderAction action, String fromStatus, String toStatus,
            String reason, String operatorSubjectNo, LocalDateTime createdAt) {
    }

    /** 本方订单分页（B4-1/B12-1：需方或供方；创建时间倒序；myRole 标注）。 */
    public PageResult<OrderSummary> mine(final String subjectNo, final OrderStatus status,
            final Long productId, final String contractNo, final PageQuery page) {
        final PageResult<Order> orders = orderRepository.pageMine(subjectNo, status, productId,
                contractNo, page);
        return toSummaries(orders, page, subjectNo);
    }

    /** 运营方全量分页检索（B4-2；不做归属过滤；零写订单状态；界面归 3.7.7）。 */
    public PageResult<OrderSummary> governance(final OrderStatus status, final Long productId,
            final String contractNo, final String requesterNo, final PageQuery page) {
        final PageResult<Order> orders = orderRepository.pageGovernance(status, productId,
                contractNo, requesterNo, page);
        return toSummaries(orders, page, null);
    }

    /**
     * 订单详情（R-OR2）：需方/供方可读；非本方与不存在同码同文 1011C0002（防枚举）+
     * 越权拒绝留痕；含留痕时间线（用户读面过滤 DENIED_ACCESS）与关联合约状态。
     */
    public OrderDetail detail(final String orderNo, final String subjectNo) {
        final Order order = orderRepository.findByNo(orderNo).orElse(null);
        if (order == null) {
            // 不存在：不伪造订单行，仅应用日志（hifi §3.4）
            log.info("订单详情不存在: orderNo={}, operatorNo={}", orderNo, subjectNo);
            throw notAccessible();
        }
        final PartyRole role = order.roleOf(subjectNo);
        if (role == null) {
            // 越权：拒绝留痕（独立写入）+ 同码同文（防枚举）
            orderRepository.insertLog(new OrderActionLog(null, order.orderNo(),
                    OrderAction.DENIED_ACCESS, null, null, "非本方访问订单详情", subjectNo,
                    LocalDateTime.now(clock)));
            throw notAccessible();
        }
        final List<OrderActionView> actions = orderRepository.findLogs(orderNo).stream()
                .filter(row -> row.action() != OrderAction.DENIED_ACCESS)
                .map(row -> new OrderActionView(row.action(), row.fromStatus(), row.toStatus(),
                        row.reason(), row.operatorSubjectNo(), row.createdAt()))
                .toList();
        final String contractStatus = contractStatusReader.statusOf(order.contractNo());
        return new OrderDetail(order.orderNo(), order.status().name(), order.contractNo(),
                contractStatus, order.productId(), order.productName(),
                order.providerSubjectNo(), order.requesterSubjectNo(), order.pricingModel(),
                toPlainString(order.priceAmount()), order.createdAt(), role, order.templateNo(),
                order.templateVersionNo(), order.closedReason(), order.closedAt(), actions);
    }

    // ==== 私有 ====

    private PageResult<OrderSummary> toSummaries(final PageResult<Order> orders,
            final PageQuery page, final String subjectNo) {
        final Set<String> contractNos = orders.list().stream()
                .map(Order::contractNo).collect(Collectors.toSet());
        final Map<String, String> statuses = contractStatusReader.statusesOf(contractNos);
        final List<OrderSummary> items = orders.list().stream()
                .map(order -> new OrderSummary(order.orderNo(), order.status().name(),
                        order.contractNo(), statuses.get(order.contractNo()), order.productId(),
                        order.productName(), order.providerSubjectNo(),
                        order.requesterSubjectNo(), order.pricingModel(),
                        toPlainString(order.priceAmount()), order.createdAt(),
                        subjectNo == null ? null : order.roleOf(subjectNo)))
                .toList();
        return PageResult.of(items, orders.total(), page);
    }

    private ContractBizException notAccessible() {
        return new ContractBizException(OrderErrorCodes.ORDER_NOT_ACCESSIBLE,
                OrderErrorCodes.ORDER_NOT_ACCESSIBLE_MESSAGE);
    }

    private String toPlainString(final java.math.BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
