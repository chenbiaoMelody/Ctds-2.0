package com.ctds.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import com.ctds.common.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ctds.example.application.DemoOrderService;
import com.ctds.example.domain.DemoOrderRepository;
import com.ctds.example.domain.DemoStockRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B11 幂等与分布式锁演示集成（真实 Spring 上下文 + 真实 common-idempotency，内存模式零中间件）：
 * ①同一订单号重复提交只创建一次、返回首次结果（结果复用）②并发扣库存不超卖（锁互斥）③库存不足拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class IdempotencyLockDemoIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORDERS_URL = "/api/v1/demo/orders";
    private static final String DEDUCT_URL = "/api/v1/demo/stock/deduct";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoOrderService orderService;

    @Autowired
    private DemoStockRepository stockRepository;

    @Autowired
    private DemoOrderRepository orderRepository;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void resetDemoState() {
        orderRepository.clear();
    }

    @Test
    void 处理中错误封套与文案出站正确() {
        // 评审①P2-2：B9 封套断言——1002C 码经 GlobalExceptionHandler 出站 = 400 + 契约文案（不含内部细节）
        final ResponseEntity<ApiResult<Void>> resp = globalExceptionHandler.onBizException(
                new BizException(IdempotencyErrorCodes.IDEMPOTENCY_IN_PROGRESS,
                        IdempotencyErrorCodes.IDEMPOTENCY_IN_PROGRESS_MESSAGE));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("1002C0001");
        assertThat(resp.getBody().message()).isEqualTo("请求处理中，请稍后重试");
    }

    @Test
    void 存储不可用S码出站统一系统繁忙() {
        // 评审①P2-2：1002S 码出站统一"系统繁忙"（S 码 message 不出站，防内部实现泄露）
        final ResponseEntity<ApiResult<Void>> resp = globalExceptionHandler.onBizException(
                new BizException(IdempotencyErrorCodes.IDEMPOTENCY_STORE_UNAVAILABLE, "幂等存储不可用"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("1002S0001");
        assertThat(resp.getBody().message()).isEqualTo("系统繁忙，请稍后重试");
    }

    @Test
    void 锁超时封套与文案出站正确() {
        // 评审④P2-3：1002C0002 C 码封套 = 400 + 契约文案
        final ResponseEntity<ApiResult<Void>> resp = globalExceptionHandler.onBizException(
                new BizException(IdempotencyErrorCodes.LOCK_ACQUIRE_TIMEOUT,
                        IdempotencyErrorCodes.LOCK_ACQUIRE_TIMEOUT_MESSAGE));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("1002C0002");
        assertThat(resp.getBody().message()).isEqualTo("操作繁忙，请稍后重试");
    }

    @Test
    void 锁服务不可用S码出站统一系统繁忙() {
        // 评审④P2-3：1002S0002 S 码出站统一"系统繁忙"
        final ResponseEntity<ApiResult<Void>> resp = globalExceptionHandler.onBizException(
                new BizException(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE, "锁服务不可用"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("1002S0002");
        assertThat(resp.getBody().message()).isEqualTo("系统繁忙，请稍后重试");
    }

    @Test
    void sameOrderNoSubmittedTwiceCreatesOnceAndReturnsFirstResult() throws Exception {
        final String body = "{\"orderNo\":\"ORD-20260909-001\"}";
        final JsonNode first = submitOrder(body);
        final JsonNode second = submitOrder(body);
        assertThat(second).isEqualTo(first); // 结果复用：与首次完全一致
        assertThat(orderService.orderCount()).isEqualTo(1); // 业务只执行一次
    }

    @Test
    void differentOrderNosAreIndependent() throws Exception {
        final JsonNode a = submitOrder("{\"orderNo\":\"ORD-A\"}");
        final JsonNode b = submitOrder("{\"orderNo\":\"ORD-B\"}");
        assertThat(a.get("orderNo").asText()).isEqualTo("ORD-A");
        assertThat(b.get("orderNo").asText()).isEqualTo("ORD-B");
        assertThat(orderService.orderCount()).isEqualTo(2);
    }

    @Test
    void concurrentDeductDoesNotOversell() throws Exception {
        final int threads = 20;
        final int initial = stockRepository.current("P001");
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        mockMvc.perform(post(DEDUCT_URL)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"productId\":\"P001\",\"quantity\":1}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value("0"));
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            if (!ready.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("workers did not become ready");
            }
            go.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new AssertionError("workers did not finish");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures.get()).isZero(); // 全部成功（无超卖拒绝）
        assertThat(stockRepository.current("P001")).isEqualTo(initial - threads); // 库存恰扣 threads
    }

    @Test
    void insufficientStockRejected() throws Exception {
        mockMvc.perform(post(DEDUCT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"P001\",\"quantity\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("库存不足"));
    }

    private JsonNode submitOrder(final String body) throws Exception {
        final MvcResult result = mockMvc.perform(post(ORDERS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
