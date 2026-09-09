package com.ctds.common.idempotency.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import com.ctds.common.idempotency.lock.LockService.LockHandle;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * B10 审计联动测试（评审①P1-3 补）：锁超时记 FAILURE(timeout)、锁服务异常记 FAILURE
 * (service_unavailable)、audit-enabled=false 时零审计。
 */
@SpringJUnitConfig(LockAuditTest.AdviceTestConfig.class)
@EnableAspectJAutoProxy
class LockAuditTest {

    @Autowired
    private DemoLockService service;

    @Autowired
    private FakeAuditRecorder recorder;

    @Autowired
    private LockProperties properties;

    @Autowired
    private LockService lockService;

    @Configuration
    static class AdviceTestConfig {

        @Bean
        DemoLockService demoLockService() {
            return new DemoLockService();
        }

        @Bean
        LockService lockService() {
            return new InMemoryLockService();
        }

        @Bean
        LockProperties lockProperties() {
            final LockProperties props = new LockProperties();
            props.setMode("memory");
            props.setAuditEnabled(true);
            return props;
        }

        @Bean
        FakeAuditRecorder fakeAuditRecorder() {
            return new FakeAuditRecorder();
        }

        @Bean
        LockAdvice lockAdvice(final LockService lockService, final LockProperties properties,
                final FakeAuditRecorder recorder) {
            return new LockAdvice(lockService, properties, recorder);
        }
    }

    /** 演示业务（审计测试专用）。 */
    static class DemoLockService {

        @Locked(key = "#key", waitSeconds = 1)
        public String waiting(final String key) {
            return "ok-" + key;
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

    @Test
    void 锁超时记一条失败审计() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final CountDownLatch lockHeld = new CountDownLatch(1);
            final Future<?> holder = pool.submit(() -> {
                final Optional<LockHandle> h = lockService.tryLock("ctds:lock:busy",
                        Duration.ofMillis(100), Duration.ofSeconds(-1));
                assertThat(h).isPresent();
                lockHeld.countDown();
                sleepQuietly(2000);
                h.orElseThrow().unlock();
            });
            if (!lockHeld.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("holder did not acquire lock");
            }
            assertThrows(BizException.class, () -> service.waiting("busy"));
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).outcome()).isEqualTo(AuditOutcome.FAILURE);
    }

    @Test
    void 锁服务异常记一条失败审计() throws Throwable {
        final LockService broken = mock(LockService.class);
        when(broken.tryLock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BizException(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE, "锁服务不可用"));
        final LockAdvice advice = new LockAdvice(broken, properties, recorder);
        final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        final MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        final Method waiting = DemoLockService.class.getMethod("waiting", String.class);
        when(sig.getMethod()).thenReturn(waiting);
        when(pjp.getArgs()).thenReturn(new Object[] {"busy"});
        final BizException ex = assertThrows(BizException.class,
                () -> advice.around(pjp, waiting.getAnnotation(Locked.class)));
        assertThat(ex.getErrorCode().value()).isEqualTo(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE.value());
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().get(0).outcome()).isEqualTo(AuditOutcome.FAILURE);
    }

    @Test
    void 自定义锁服务抛通用异常统一包装锁服务不可用() throws Throwable {
        // 评审④P2-2：自定义 LockService 抛非 BizException → advice 统一收敛 1002S0002（fail-closed）
        final LockService broken = mock(LockService.class);
        when(broken.tryLock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("custom lock backend down"));
        final LockAdvice advice = new LockAdvice(broken, properties, recorder);
        final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        final MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        final Method waiting = DemoLockService.class.getMethod("waiting", String.class);
        when(sig.getMethod()).thenReturn(waiting);
        when(pjp.getArgs()).thenReturn(new Object[] {"busy"});
        final BizException ex = assertThrows(BizException.class,
                () -> advice.around(pjp, waiting.getAnnotation(Locked.class)));
        assertThat(ex.getErrorCode().value()).isEqualTo(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE.value());
        assertThat(recorder.events()).hasSize(1);
    }

    @Test
    void 审计开关关闭时零条() {
        properties.setAuditEnabled(false);
        service.waiting("free");
        assertThat(recorder.events()).isEmpty();
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
