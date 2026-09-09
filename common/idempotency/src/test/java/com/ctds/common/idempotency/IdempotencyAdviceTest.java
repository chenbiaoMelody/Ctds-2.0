package com.ctds.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 幂等切面契约测试（hifi B2-B5）：Spring AOP 代理 + InMemory 实现，断言
 * 首次执行/结果复用/互不影响/异常重试/处理中错误/过期重执行/空键拒绝。
 */
@SpringJUnitConfig(IdempotencyAdviceTest.AdviceTestConfig.class)
@EnableAspectJAutoProxy
class IdempotencyAdviceTest {

    @Autowired
    private DemoService service;

    @Autowired
    private InMemoryIdempotencyStore store;

    private static final Duration PROCESSING_TTL = Duration.ofMillis(50);
    private static final long RESULT_EXPIRE_SECONDS = 1;

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
            props.setProcessingTtl(PROCESSING_TTL);
            props.setDefaultExpireSeconds(RESULT_EXPIRE_SECONDS);
            props.setAuditEnabled(false);
            return props;
        }

        @Bean
        IdempotencyAdvice idempotencyAdvice(final InMemoryIdempotencyStore store,
                final IdempotencyProperties properties) {
            return new IdempotencyAdvice(store, properties, null);
        }
    }

    /** 演示业务：幂等提交 + 失败重试 + 慢业务（处理中窗口）。计数经方法读写（AOP 代理下字段不可直接访问）。 */
    static class DemoService {

        private final AtomicInteger submitCalls = new AtomicInteger();
        private final AtomicInteger failureAttempts = new AtomicInteger();

        @Idempotent(key = "#orderNo")
        public String submit(final String orderNo) {
            return "result-" + submitCalls.incrementAndGet() + "-" + orderNo;
        }

        @Idempotent(key = "#orderNo")
        public String submitWithFailure(final String orderNo) {
            if (failureAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("transient failure");
            }
            return "ok-" + orderNo;
        }

        @Idempotent(key = "#orderNo")
        public String slowSubmit(final String orderNo, final CountDownLatch entered,
                final CountDownLatch release) throws InterruptedException {
            entered.countDown();
            release.await();
            return "slow-" + orderNo;
        }

        public int submitCallCount() {
            return submitCalls.get();
        }

        public int failureAttemptCount() {
            return failureAttempts.get();
        }

        public void reset() {
            submitCalls.set(0);
            failureAttempts.set(0);
        }
    }

    @BeforeEach
    void setUp() {
        service.reset();
        store.release(storeKey("A"));
        store.release(storeKey("B"));
    }

    private static String storeKey(final String raw) {
        return "ctds:idem:" + raw;
    }

    @Test
    void 首次执行返回结果() {
        assertEquals("result-1-A", service.submit("A"));
        assertEquals("result-2-B", service.submit("B"));
    }

    @Test
    void 重复请求返回首次结果且不重复执行() {
        final String first = service.submit("A");
        final String second = service.submit("A");
        assertEquals(first, second);
        assertEquals("result-1-A", second);
    }

    @Test
    void 不同幂等键互不影响() {
        assertEquals("result-1-A", service.submit("A"));
        assertEquals("result-2-B", service.submit("B"));
        assertEquals("result-1-A", service.submit("A"));
    }

    @Test
    void 业务异常后同键可重试且不返回失败结果() {
        assertThrows(IllegalStateException.class, () -> service.submitWithFailure("A"));
        assertEquals("ok-A", service.submitWithFailure("A"));
        // 重试成功的结果已缓存：第三次直接返回
        assertEquals("ok-A", service.submitWithFailure("A"));
    }

    @Test
    void 并发同键第二请求返回处理中错误() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<?> first = pool.submit(() -> {
                try {
                    service.slowSubmit("A", entered, release);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            if (!entered.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("first request did not enter business method");
            }
            final BizException ex = assertThrows(BizException.class, () -> service.slowSubmit("A", entered, release));
            assertEquals(IdempotencyErrorCodes.IDEMPOTENCY_IN_PROGRESS.value(), ex.getErrorCode().value());
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 结果过期后同键重新执行() throws InterruptedException {
        assertEquals("result-1-A", service.submit("A"));
        Thread.sleep(RESULT_EXPIRE_SECONDS * 1000L + 200);
        assertEquals("result-2-A", service.submit("A"));
    }

    @Test
    void 空键拒绝快速失败() {
        final BizException ex = assertThrows(BizException.class, () -> service.submit(null));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }
}
