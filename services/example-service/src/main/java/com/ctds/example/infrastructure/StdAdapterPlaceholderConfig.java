package com.ctds.example.infrastructure;

import com.ctds.std.did.DidInteropStandardConfig;
import com.ctds.std.evidence.EvidenceStandardApi;
import com.ctds.std.evidence.PlaceholderEvidenceStandardApi;
import com.ctds.std.interconnect.InterconnectStandardApi;
import com.ctds.std.interconnect.PlaceholderInterconnectStandardApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 标准适配层接线（WBS 2.4.8 H6，WBS-3.1.10 替换 did 域）：互联互通与测评证据域仍为占位实现；
 * <b>did 域已由真实实现替换</b>——不再注册占位 Bean，改为引入 std-adapter 的互认域装配
 * （占位类 {@code PlaceholderDidInteropStandardApi} 已按 ADR-008 §3.4 删除，替换而非并存）。
 * <p>演示壳服务无本空间身份能力（未提供 {@code LocalDidStatusPort}）→ 出向验证语义在演示壳内为不可用，
 * 互认演示端点由 did 服务承载。</p>
 */
@Configuration
@Import(DidInteropStandardConfig.class)
public class StdAdapterPlaceholderConfig {

    @Bean
    public InterconnectStandardApi interconnectStandardApi() {
        return new PlaceholderInterconnectStandardApi();
    }

    @Bean
    public EvidenceStandardApi evidenceStandardApi() {
        return new PlaceholderEvidenceStandardApi();
    }
}
