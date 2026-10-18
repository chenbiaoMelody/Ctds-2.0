package com.ctds.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 幂等"结果过期后同键重新执行"契约测试（DB-46 修复，卡2a Q2-A 拆分产物）。
 *
 * <p>与 {@link IdempotencyAdviceTest}（主类）形成对照：主类持生产默认窗口（60s/600s）验证
 * "窗口内去重"（去重语义不依赖窗口长度）；本类<strong>显式覆写为短窗口</strong>（1s/1s）
 * 验证"窗口过期重执行"——该语义必须依赖短窗口，故独立成类、自含夹具。
 *
 * <p>稳健性口径：测试体沿用 over-wait sleep（TTL + 200ms，只会多等不会少等）——负载下更慢
 * 也只使过期更确定，无反向脆性；窗口长度本身由 {@link IdempotencyProperties} 默认值派生
 * （不复制字面量，属性默认值变化时本类随动、语义仍成立）。
 */
@SpringJUnitConfig(IdempotencyAdviceExpiryTest.ExpiryTestConfig.class)
@EnableAspectJAutoProxy
class IdempotencyAdviceExpiryTest {

    private static final Duration PROCESSING_TTL = Duration.ofSeconds(1);
    private static final long RESULT_EXPIRE_SECONDS = 1;

    @Autowired
    private DemoService service;

    @Configuration
    static class ExpiryTestConfig {

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

    /** 最小自含夹具：仅 {@code submit} 计数方法（主类夹具不抽取共享文件，避免跨类耦合）。 */
    static class DemoService {

        private final AtomicInteger submitCalls = new AtomicInteger();

        @Idempotent(key = "#orderNo")
        public String submit(final String orderNo) {
            return "result-" + submitCalls.incrementAndGet() + "-" + orderNo;
        }

        public int submitCallCount() {
            return submitCalls.get();
        }
    }

    @Test
    void 结果过期后同键重新执行() throws InterruptedException {
        assertEquals("result-1-A", service.submit("A"));
        Thread.sleep(RESULT_EXPIRE_SECONDS * 1000L + 200);
        assertEquals("result-2-A", service.submit("A"));
    }
}
