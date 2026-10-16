package com.ctds.gateway.config;

import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单调纳秒时钟装配（hifi §3-2）：限流令牌桶"补充速率"的唯一时间来源。
 *
 * <p>默认 = {@link System#nanoTime}（单调、不受系统时钟调整影响，进程内令牌桶的正确时间语义）；
 * 测试以 {@code @Primary} 替身注入可控时钟，使"第 N+1 次被拒"与"补充后恢复放行"两条判定不依赖
 * 用例执行耗时（挂钟推进），消除假红风险——生产装配零行为变化。</p>
 */
@Configuration
public class GatewayClockConfig {

    @Bean
    public LongSupplier gatewayNanoClock() {
        return System::nanoTime;
    }
}
