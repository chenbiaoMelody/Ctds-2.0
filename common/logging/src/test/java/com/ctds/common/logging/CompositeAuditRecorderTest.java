package com.ctds.common.logging;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 组合审计出口单测（hifi §8 TC4 装配分支伴随）：record 顺序委托 file→jdbc、
 * shutdown 级联委托（Bean destroyMethod="shutdown" 兼容）。
 */
class CompositeAuditRecorderTest {

    private final AsyncFileAuditRecorder file = mock(AsyncFileAuditRecorder.class);
    private final JdbcMeteringAuditRecorder jdbc = mock(JdbcMeteringAuditRecorder.class);
    private final CompositeAuditRecorder composite = new CompositeAuditRecorder(file, jdbc);

    private AuditEvent event() {
        return new AuditEvent("evt-1", Clock.systemUTC().instant(), "S-req-1",
                "gateway.delivery.metering", "delivery", "CO100001", AuditOutcome.SUCCESS,
                Map.of("contractNo", "CO100001"));
    }

    @Test
    void recordDelegatesToBothLegsInOrder() {
        final AuditEvent event = event();
        composite.record(event);
        final InOrder order = inOrder(file, jdbc);
        order.verify(file).record(event);
        order.verify(jdbc).record(event);
        order.verifyNoMoreInteractions();
    }

    @Test
    void sameEventInstanceReachesBothLegs() {
        final AuditEvent event = event();
        composite.record(event);
        // 事件对象原样透传（不做拷贝改写——file 腿序列化与 jdbc 腿取值同源）
        verify(file).record(org.mockito.ArgumentMatchers.argThat(e -> e == event));
        verify(jdbc).record(org.mockito.ArgumentMatchers.argThat(e -> e == event));
    }

    @Test
    void shutdownCascadesToBothLegs() {
        composite.shutdown();
        verify(file).shutdown();
        verify(jdbc).shutdown();
    }
}
