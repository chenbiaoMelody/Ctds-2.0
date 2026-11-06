package com.ctds.evidence.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 存证测试固定时钟（hifi §3「Clock 注入」）：取号日期、created_at / updated_at、
 * 事件时点未来校验都以 {@code clock.instant()} 为准，真实时钟会让边界用例成为时间炸弹。
 * 固定时点 2026-11-05T13:30:00Z：UTC 日期恒为 20261105，+6 分钟未来用例恒定越界。
 */
@TestConfiguration
public class FixedClockTestConfiguration {

    /** 固定时点（UTC）。 */
    public static final Instant FIXED_INSTANT = Instant.parse("2026-11-05T13:30:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
    }
}
