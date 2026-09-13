package com.ctds.subject.infrastructure;

import com.ctds.std.certification.CertificationStandardApi;
import com.ctds.std.certification.MockCertificationChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 认证能力 Bean 装配（WBS-3.1.3；ADR-008 第 4 条替换规则：使用方 Bean 注册即替换点——
 * 接入真实认证渠道时替换本 {@link CertificationStandardApi} 注册，业务代码零改动）。
 */
@Configuration
public class CertificationBeanConfig {

    /**
     * 认证渠道 = 模拟实现（规格 C-1.1 行为 7 Q1 裁决：演示期零外部采购，预置规则支撑剧本可重复执行）。
     * 异常注入开关仅演示/测试 fail-fast 口径使用（hifi B8）。
     */
    @Bean
    public CertificationStandardApi certificationStandardApi(
            @Value("${ctds.std.certification.mock.channel-error:false}") final boolean mockChannelError) {
        return new MockCertificationChannel(mockChannelError);
    }

    /** 系统时钟（可注入：核验重试上限"次日自动恢复"的测试拨针口径，hifi B7）。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
