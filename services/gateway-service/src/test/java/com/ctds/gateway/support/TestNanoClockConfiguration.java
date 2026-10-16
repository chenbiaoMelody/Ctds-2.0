package com.ctds.gateway.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 网关测试共用可控纳秒时钟（hifi §3-2 的 {@code gatewayNanoClock} 替身）：以 {@code @Primary} 覆盖
 * 生产装配的 {@link System#nanoTime}，使限流用例的"补充速率"推进完全由用例决定——
 * 不再依赖"请求在 1 秒内连打"这类执行耗时假设（评审④ P1：挂钟依赖 = 假红风险）。
 *
 * <p>用法：{@code NANOS.set(0L)} 复位刻度，{@code NANOS.addAndGet(1_000_000_000L)} 推进 1 秒。</p>
 *
 * <p>边界（复审 P3 登记）：刻度为<b>静态全局值</b>，当前 surefire 未开启并行、且限流用例按主体（subject）
 * 隔离令牌桶，故无串扰；若后续对本模块开启测试并行，须改为一用例一刻度实例（否则并行的限流用例
 * 共享刻度会造成假红/假绿）。</p>
 */
@TestConfiguration
public class TestNanoClockConfiguration {

    /** 测试用单调纳秒刻度（全局唯一来源；用例自行复位/推进）。 */
    public static final AtomicLong NANOS = new AtomicLong();

    @Bean
    @Primary
    public LongSupplier testNanoClock() {
        return NANOS::get;
    }
}
