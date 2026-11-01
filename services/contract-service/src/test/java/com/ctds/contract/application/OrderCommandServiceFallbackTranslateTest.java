package com.ctds.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.Order;
import com.ctds.contract.domain.OrderRepository;
import com.ctds.contract.domain.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单并发兜底分译单测（WBS-3.7.3 hifi §3.2；T11 并发唯一补充腿）：uk 唯一索引兜底
 * {@code DuplicateKeyException} 的回查分译——回查<b>命中</b>进行中订单 → 1011C0001
 * （与预检查同义）。该腿在单线程集成面不可达（预检查与 uk_active_order_key 同键位，
 * 预检查必先命中），故以存根直调钉住分译逻辑；C0004 腿 + 事务整体回滚由
 * {@code ProductOrderFlowIntegrationTest} t11 以订单号占位法承载。
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandServiceFallbackTranslateTest {

    @Mock
    private ContractInitiationService initiationService;
    @Mock
    private OrderRepository orderRepository;

    @Test
    void duplicateKeyFallbackWithActiveOrderTranslatesToC0001() {
        // ① 预检查通过（空）→ 兜底回查命中（并发对手单此刻可见）→ 分译 C0001
        given(orderRepository.findActive("S-req", 12L))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(new Order(null, "OD000009", 12L, "快照产品", "S-prov",
                        "S-req", "PER_CALL", null, "CT000001", 1, "CO000009",
                        OrderStatus.IN_PROGRESS, null, null, "S-req", null, null)));
        given(initiationService.prepare(any())).willReturn(
                new ContractInitiationService.PreparedInitiation(
                        new InitiateCommand("S-req", 12L, "CT000001", 1, null, ""),
                        null, null, null));
        given(initiationService.persist(any())).willThrow(
                new DuplicateKeyException("uk_active_order_key"));

        final OrderCommandService service = new OrderCommandService(initiationService,
                orderRepository, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(new OrderCommandService.CreateOrderCommand(
                        "S-req", 12L, "CT000001", 1, null, "req-fallback")))
                .isInstanceOfSatisfying(ContractBizException.class, ex -> assertThat(
                        ex.getErrorCode().value()).isEqualTo("1011C0001"));
    }
}
