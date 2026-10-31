package com.ctds.contract.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.Order;
import com.ctds.contract.domain.OrderAction;
import com.ctds.contract.domain.OrderActionLog;
import com.ctds.contract.domain.OrderErrorCodes;
import com.ctds.contract.domain.OrderRepository;
import com.ctds.contract.domain.OrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单写面应用服务（WBS-3.7.3 hifi §3.2；规格 B3-1~B3-4）。订单 = 合约业务外壳：
 * 订单登记与合约草案<b>同事务</b>落库（TransactionTemplate 外层——合约 persist 三写 +
 * 订单取号/落库/CREATE 留痕原子提交，任一失败整体回滚不残留合约）。
 *
 * <p>幂等双保险（Q6-A）：应用服务 {@code @Idempotent} 键 = 需方 + 客户端 requestId（TTL 内
 * 同键重放返回首次结果）；<b>订单专用发起通路绕开 W5 内容幂等注解</b>（W5 幂等键含条款指纹
 * ——订单关闭后同内容重购会命中已终止合约缓存，故直接调 {@link ContractInitiationService}
 * prepare/persist，不走 ContractCommandService.initiate）；DB uk_active_order_key 生成列
 * 唯一索引兜底并发（B3-2 同需方同产品仅一份进行中订单）。</p>
 */
@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);
    /** 订单编号前缀（OD + 6 位全局序号，沿 CO 先例）。 */
    private static final String ORDER_NO_PREFIX = "OD";
    /** requestId 长度上限（hifi §1.1：UUID 形态 8~64 字符；控制器前置校验零业务请求）。 */
    private static final int REQUEST_ID_MAX = 64;

    private final ContractInitiationService contractInitiationService;
    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderCommandService(final ContractInitiationService contractInitiationService,
            final OrderRepository orderRepository, final TransactionTemplate transactionTemplate,
            final Clock clock) {
        this.contractInitiationService = contractInitiationService;
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 创建命令（requesterNo 由控制器取 AuthContext；requestId 客户端弹窗生成一次）。 */
    public record CreateOrderCommand(String requesterNo, long productId, String templateNo,
            int templateVersionNo, JsonNode clauseValues, String requestId) {
    }

    /** 创建结果（快照与合约落库同源——B3-3；hifi §1.4 OrderResult）。 */
    public record OrderResult(String orderNo, String status, String contractNo,
            String contractStatus, long productId, String productName, String providerSubjectNo,
            String requesterSubjectNo, String pricingModel, String priceAmount, String templateNo,
            int templateVersionNo, LocalDateTime createdAt) {
    }

    /**
     * 创建订单（W-OR1；201）：预检查进行中订单（1011C0001）→ 合约门槛链 prepare（事务外）
     * → 事务 { 合约 persist + 订单取号落库 + CREATE 留痕 } → 结果快照同源组装。
     */
    @Idempotent(key = "'ORDER:' + #cmd.requesterNo + ':' + #cmd.requestId")
    public OrderResult create(final CreateOrderCommand cmd) {
        // ① 预检查（事务外；B3-2 进行中订单唯一）
        if (orderRepository.findActive(cmd.requesterNo(), cmd.productId()).isPresent()) {
            throw new ContractBizException(OrderErrorCodes.ORDER_DUPLICATED,
                    OrderErrorCodes.ORDER_DUPLICATED_MESSAGE);
        }
        // ② 合约门槛链（ADMITTED / 在架 / 异主体 / 模板 / 条款——事务外，合约域单点承担）
        final ContractInitiationService.PreparedInitiation prepared =
                contractInitiationService.prepare(toInitiateCommand(cmd));
        // ③ 同事务：合约草案三写 + 订单登记（失败整体回滚，不残留合约）
        try {
            final OrderResult result = transactionTemplate.execute(status -> {
                final InitiateResult contractResult = contractInitiationService.persist(prepared);
                final LocalDateTime now = LocalDateTime.now(clock);
                final String orderNo = ORDER_NO_PREFIX
                        + String.format("%06d", orderRepository.nextOrderNoSeq());
                final CatalogProductPort.CatalogProduct product = prepared.product();
                final Order order = new Order(null, orderNo, product.productId(),
                        product.productName(), product.providerSubjectNo(), cmd.requesterNo(),
                        product.pricingModel(), product.priceAmount(), cmd.templateNo(),
                        cmd.templateVersionNo(), contractResult.contractNo(),
                        OrderStatus.IN_PROGRESS, null, null, cmd.requesterNo(), now, now);
                orderRepository.create(order, new OrderActionLog(null, orderNo, OrderAction.CREATE,
                        null, OrderStatus.IN_PROGRESS.name(), null, cmd.requesterNo(), now));
                return toResult(order, contractResult.status());
            });
            return result;
        } catch (final DuplicateKeyException e) {
            // 并发兜底（hifi §3.2）：回查进行中订单 → 1011C0001 同义（预检查与唯一索引竞态）；
            // 其他唯一键冲突（uk_order_no 撞号等）→ 1011C0004。事务已整体回滚，不残留合约。
            if (orderRepository.findActive(cmd.requesterNo(), cmd.productId()).isPresent()) {
                throw new ContractBizException(OrderErrorCodes.ORDER_DUPLICATED,
                        OrderErrorCodes.ORDER_DUPLICATED_MESSAGE);
            }
            log.warn("订单并发写冲突(唯一索引): requesterNo={}, productId={}",
                    cmd.requesterNo(), cmd.productId());
            throw new ContractBizException(OrderErrorCodes.ORDER_CONCURRENT_CONFLICT,
                    OrderErrorCodes.ORDER_CONCURRENT_CONFLICT_MESSAGE);
        }
    }

    /** requestId 参数校验（控制器与防御腿共用；空白/超长 → 1011C0003 零业务请求）。 */
    public static void validateRequestId(final String requestId) {
        if (requestId == null || requestId.isBlank()
                || requestId.trim().length() > REQUEST_ID_MAX) {
            throw new ContractBizException(OrderErrorCodes.ORDER_PARAM_INVALID,
                    OrderErrorCodes.ORDER_PARAM_INVALID_MESSAGE);
        }
    }

    private InitiateCommand toInitiateCommand(final CreateOrderCommand cmd) {
        // 订单专用通路不经过 W5 内容幂等注解，requestFingerprint 不参与本链路（占位空串）
        return new InitiateCommand(cmd.requesterNo(), cmd.productId(), cmd.templateNo(),
                cmd.templateVersionNo(), cmd.clauseValues(), "");
    }

    private OrderResult toResult(final Order order, final String contractStatus) {
        return new OrderResult(order.orderNo(), order.status().name(), order.contractNo(),
                contractStatus, order.productId(), order.productName(),
                order.providerSubjectNo(), order.requesterSubjectNo(), order.pricingModel(),
                order.priceAmount() == null ? null
                        : order.priceAmount().toPlainString(),
                order.templateNo(), order.templateVersionNo(), order.createdAt());
    }
}
