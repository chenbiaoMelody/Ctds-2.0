package com.ctds.metering.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 计费测试固定时钟（hifi §9「测试固定时钟」/ §11 TB6；评审修复 P1-1）：
 * 周期判定用 {@code YearMonth.now(clock)}，真实日历会把"未来周期 400"与 {@code PERIOD} 断言
 * 变成时间炸弹（2026-12-01 起必红）。固定时点 2026-11-02T00:00:00Z——任一常见时区下
 * 本地日期都落在 2026 年 11 月，故"当前月 = 2026-11 / 上月 = 2026-10"恒定。
 */
@TestConfiguration
public class FixedClockTestConfiguration {

    /** 固定时点（UTC；时区取 JVM 默认以贴近生产 bean 口径）。 */
    public static final Instant FIXED_INSTANT = Instant.parse("2026-11-02T00:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());
    }
}
