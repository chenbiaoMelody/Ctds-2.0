package com.ctds.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * B10 审计联动测试（评审①P1-3 补）：幂等命中记 SUCCESS、处理中记 FAILURE、
 * audit-enabled=false 时零审计。
 */
@SpringJUnitConfig(IdempotencyAuditTest.AdviceTestConfig.class)
@EnableAspectJAutoProxy
class IdempotencyAuditTest {

    @Autowired
    private DemoService service;

    @Autowired
    private FakeAuditRecorder recorder;

    @Autowired
    private IdempotencyProperties properties;

    @Autowired
    private InMemoryIdempotencyStore store;

    @Configuration
    static class AdviceTestConfig {

        @Bean
        DemoService demoService() {
            return new DemoService();
        }

        @Bean
        InMemoryIdempotencyStore idempotencyStore() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        IdempotencyProperties idempotencyProperties() {
            final IdempotencyProperties props = new IdempotencyProperties();
            props.setMode("memory");
            props.setProcessingTtlSeconds(Duration.ofSeconds(1));
            props.setDefaultExpireSeconds(1);
            props.setAuditEnabled(true);
            return props;
        }

        @Bean
        FakeAuditRecorder fakeAuditRecorder() {
            return new FakeAuditRecorder();
        }

        @Bean
        IdempotencyAdvice idempotencyAdvice(final InMemoryIdempotencyStore store,
                final IdempotencyProperties properties, final FakeAuditRecorder recorder) {
            return new IdempotencyAdvice(store, properties, recorder);
        }
    }

    /** 演示业务（审计测试专用，含慢业务制造处理中窗口）。 */
    static class DemoService {

        private final AtomicInteger calls = new AtomicInteger();

        @Idempotent(key = "#orderNo")
        public String submit(final String orderNo) {
            return "r-" + calls.incrementAndGet() + "-" + orderNo;
        }

        @Idempotent(key = "#orderNo")
        public String slow(final String orderNo, final CountDownLatch entered, final CountDownLatch release)
                throws InterruptedException {
            entered.countDown();
            release.await();
            return "slow-" + orderNo;
        }
    }

    /** 内存审计记录器（注入 advice 替代真实 AsyncFileAuditRecorder）。 */
    static class FakeAuditRecorder implements AuditRecorder {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(final AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> events() {
            return events;
        }
    }

    @AfterEach
    void tearDown() {
        recorder.events().clear();
        properties.setAuditEnabled(true);
    }

    @BeforeEach
    void resetState() {
        // 测试共享 Spring 上下文：清理幂等键状态（跨测试残留会改变"首次/命中"语义）
        store.release("ctds:idem:A");
        recorder.events().clear();
        properties.setAuditEnabled(true);
    }

    @Test
    void 幂等命中记一条成功审计() {
        service.submit("A");
        service.submit("A");
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).outcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void 处理中记一条失败审计() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<?> first = pool.submit(() -> {
                try {
                    service.slow("A", entered, release);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            if (!entered.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("first request did not enter business method");
            }
            assertThrows(BizException.class, () -> service.slow("A", entered, release));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).outcome()).isEqualTo(AuditOutcome.FAILURE);
    }

    @Test
    void 审计开关关闭时零条() {
        properties.setAuditEnabled(false);
        service.submit("A");
        service.submit("A");
        assertThat(recorder.events()).isEmpty();
    }
}
