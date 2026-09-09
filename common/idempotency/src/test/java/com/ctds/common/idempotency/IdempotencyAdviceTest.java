package com.ctds.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
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

    @Autowired
    private IdempotencyProperties properties;

    private static final Duration PROCESSING_TTL = Duration.ofSeconds(1);
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
            props.setProcessingTtlSeconds(PROCESSING_TTL);
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
        private final AtomicInteger nullCalls = new AtomicInteger();

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

        @Idempotent(key = "#order.orderNo")
        public String submitByObject(final DemoOrder order) {
            return "obj-" + order.orderNo();
        }

        @Idempotent(key = "#key")
        public String returnsNull(final String key) {
            nullCalls.incrementAndGet();
            return null;
        }

        @Idempotent(key = "#key")
        public CyclicPayload cyclic(final String key) {
            return new CyclicPayload();
        }

        public int submitCallCount() {
            return submitCalls.get();
        }

        public int failureAttemptCount() {
            return failureAttempts.get();
        }

        public int nullCallCount() {
            return nullCalls.get();
        }

        public void reset() {
            submitCalls.set(0);
            failureAttempts.set(0);
            nullCalls.set(0);
        }
    }

    /** SpEL 对象求值参数（评审④P1-2：SpEL 求值异常分支测试用）。 */
    record DemoOrder(String orderNo) {
    }

    /** 自引用对象：Jackson 序列化必然失败（评审④P2-6：序列化失败 → 1000S9999）。 */
    static class CyclicPayload {
        public final CyclicPayload self;

        CyclicPayload() {
            this.self = this;
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
    @org.junit.jupiter.api.Timeout(5)
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

    @Test
    void 存储不可用拒绝执行业务failClosed() throws Throwable {
        // 评审②P2-1：tryAcquire 存储异常 → 1002S0001，业务不执行（fail-closed，防重复执行）
        final IdempotencyStore broken = mock(IdempotencyStore.class);
        when(broken.tryAcquire(any(), any())).thenThrow(new RuntimeException("redis down"));
        final IdempotencyAdvice advice = new IdempotencyAdvice(broken, properties, null);
        final ProceedingJoinPoint pjp = joinPointOf("submit", new Object[] {"A"});
        final BizException ex = assertThrows(BizException.class,
                () -> advice.around(pjp, submitAnnotation()));
        assertEquals("1002S0001", ex.getErrorCode().value());
        verify(broken, never()).release(any());
        assertEquals(0, service.submitCallCount());
    }

    @Test
    void 读结果存储异常返回存储不可用() throws Throwable {
        // 评审②P2-1：重复请求读结果存储异常 → 1002S0001（与 tryAcquire 同口径 fail-closed）
        final IdempotencyStore broken = mock(IdempotencyStore.class);
        when(broken.tryAcquire(any(), any())).thenReturn(false);
        when(broken.getResult(any())).thenThrow(new RuntimeException("redis down"));
        final IdempotencyAdvice advice = new IdempotencyAdvice(broken, properties, null);
        final BizException ex = assertThrows(BizException.class,
                () -> advice.around(joinPointOf("submit", new Object[] {"A"}), submitAnnotation()));
        assertEquals("1002S0001", ex.getErrorCode().value());
    }

    @Test
    void 写结果存储异常返回存储不可用并释放执行权() throws Throwable {
        // 评审②P2-1：complete 存储异常 → 1002S0001 + 释放执行权（可重试）
        final IdempotencyStore broken = mock(IdempotencyStore.class);
        when(broken.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(broken).complete(any(), any(), any());
        final IdempotencyAdvice advice = new IdempotencyAdvice(broken, properties, null);
        final BizException ex = assertThrows(BizException.class,
                () -> advice.around(joinPointOf("submit", new Object[] {"A"}), submitAnnotation()));
        assertEquals("1002S0001", ex.getErrorCode().value());
        verify(broken).release(any());
    }

    @Test
    void 超长键拒绝快速失败() throws Throwable {
        // 评审②P2-2：键长度超限 → 1000C0001（防超长键对 Redis 内存压力）
        final String longKey = "K".repeat(SpelKeyResolver.MAX_KEY_LENGTH + 1);
        final BizException ex = assertThrows(BizException.class, () -> service.submit(longKey));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }

    @Test
    void SpEL求值异常拒绝快速失败() {
        // 评审④P1-2：key="#order.orderNo" 且 order=null → 求值异常 → 1000C0001（业务不执行）
        final BizException ex = assertThrows(BizException.class, () -> service.submitByObject(null));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
        assertEquals(0, service.submitCallCount());
    }

    @Test
    void 空对象键拒绝快速失败() {
        // key="#order.orderNo" 且 order.orderNo=null → 求值结果为 null → 空键 → 1000C0001
        final BizException ex = assertThrows(BizException.class,
                () -> service.submitByObject(new DemoOrder(null)));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }

    @Test
    void 返回null视为合法结果且重复返回null() {
        // 评审④P2-6：null 返回值合法（缓存完成态）；重复请求返回 null 且不重复执行
        assertThat(service.returnsNull("A")).isNull();
        assertThat(service.returnsNull("A")).isNull();
        assertThat(service.nullCallCount()).isEqualTo(1);
    }

    @Test
    void 返回值不可序列化转为内部错误并可重试() {
        // 评审④P2-6：自引用对象序列化必然失败 → 1000S9999 + 释放执行权（可重试）
        final BizException ex = assertThrows(BizException.class, () -> service.cyclic("A"));
        assertEquals(ErrorCodes.INTERNAL_ERROR.value(), ex.getErrorCode().value());
        // 释放后同键可重试（仍失败，但证明执行权已释放、无永久占用）
        final BizException again = assertThrows(BizException.class, () -> service.cyclic("A"));
        assertEquals(ErrorCodes.INTERNAL_ERROR.value(), again.getErrorCode().value());
    }

    private static ProceedingJoinPoint joinPointOf(final String methodName, final Object[] args)
            throws NoSuchMethodException {
        final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        final MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        final Method method = DemoService.class.getMethod(methodName, String.class);
        when(sig.getMethod()).thenReturn(method);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    private static Idempotent submitAnnotation() throws NoSuchMethodException {
        return DemoService.class.getMethod("submit", String.class).getAnnotation(Idempotent.class);
    }
}
